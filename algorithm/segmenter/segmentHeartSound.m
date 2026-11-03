function [out, dbg] = segmentHeartSound(audio, Fs, modelPath)
%SEGMENTHEARTSOUND  Heart sound segmentation, end to end, in one file.
%
%   out        = segmentHeartSound(audio, Fs)
%   out        = segmentHeartSound(audio, Fs, 'model.json')
%   [out, dbg] = segmentHeartSound(...)   also returns every intermediate
%                                         stage, for checking a port
%
% INPUT   audio  mono samples, any sample rate, any scaling
%         Fs     that sample rate in Hz
%
% OUTPUT  out.s1Times      S1 onset times, seconds
%         out.bpm          heart rate from S1-to-S1 intervals
%         out.bpmCV        beat-to-beat variability (0.03 regular, >0.2 irregular)
%         out.states       decoded state per frame, 1=S1 2=systole 3=S2 4=diastole
%         out.frameTimes   time in seconds of each frame
%         out.posteriors   nFrames x 4 probabilities
%         out.confidence   mean top probability; ~0.95 good, <0.6 unreliable
%         out.stateMedians median duration of each state, ms
%
% ---------------------------------------------------------------------
% THIS FILE IS THE SPECIFICATION. Port it top to bottom.
%
% It deliberately does NOT call the Deep Learning Toolbox. Every operation
% is written out so it can be translated directly. It needs only:
%   resample()  - Signal Processing Toolbox (replace with any resampler)
%   jsondecode() - built in
%
% Verified to reproduce the trained network's output exactly. If a port
% reproduces testvectors.json it is correct; see verifyPort.m.
% ---------------------------------------------------------------------

if nargin < 3, modelPath = 'model.json'; end

%% ====================================================================
%  STAGE 1 - PREPROCESSING
%  ====================================================================
TARGET_FS = 2000;

% 1a. Resample. NOT optional: the network learned absolute durations. S1 is
%     128 ms = 256 samples at 2 kHz. At 16 kHz every sound looks 8x longer
%     than anything it saw in training.
audio = double(audio(:));
if Fs ~= TARGET_FS
    audio = resample(audio, TARGET_FS, Fs);
end

% 1b. Bandpass 25-400 Hz, 4th-order Butterworth, ZERO PHASE.
%     Coefficients are hardcoded so no filter designer is needed.
%     Zero phase = filter forward, reverse, filter again, reverse back.
%     A one-directional filter shifts every sound later in time and the
%     detections come out systematically late.
[B, A] = localButterCoeffs();          % see bottom of file
x = filtfiltSimple(B, A, audio);
xFilt = x;                             % kept for the dbg output

% 1c. Robust normalisation. The 99.5th percentile, not the maximum, so a
%     single click cannot rescale the whole recording. Then clip.
scale = prctileSimple(abs(x), 99.5);
if ~isfinite(scale) || scale <= 0, scale = max(abs(x)); end
if scale <= 0, scale = 1; end
x = max(min(x/scale, 8), -8);

%% ====================================================================
%  STAGE 2 - SLIDING WINDOW
%  ====================================================================
M = jsondecode(fileread(modelPath));
WIN  = M.inputLength;      % 4096 samples = 2.048 s
HOP  = 32;                 % 16 ms between frames. Raise to 64/128 to halve/quarter
                           % the compute; detections get correspondingly coarser.
half = WIN/2;
N    = numel(x);

% Reflect-pad so a window can centre on the very first and last sample.
% Without this, any S1 in the opening second is unreachable.
xp = [flipud(x(2:half+1)); x; flipud(x(N-half:N-1))];

centres = (1:HOP:N)';
nF      = numel(centres);
post    = zeros(nF, 4);

for i = 1:nF
    c = centres(i);
    post(i,:) = forwardPass(xp(c+1 : c+WIN), M);
end

%% ====================================================================
%  STAGE 3 - VITERBI DECODE
%  ====================================================================
% The heart only moves one way: S1 -> systole -> S2 -> diastole -> S1.
% Each frame may STAY in its state or ADVANCE to the next. Nothing else.
% Frame-wise argmax has no such constraint and will emit S1,S2,S1,S2 inside
% a single systole on noisy audio.
pStay = [0.8752; 0.9060; 0.8440; 0.9565];   % fitted from training annotations
states = decodeViterbi(post, pStay);

%% ====================================================================
%  STAGE 4 - EVENTS AND HEART RATE
%  ====================================================================
% Each run of consecutive frames in one state becomes ONE detection, placed
% at the most confident frame in that run. One per run, not one per frame.
MIN_RUN = 2;
% Frame index -> seconds. centres are 1-based sample indices; the pipeline
% that produced the published results uses c/Fs rather than (c-1)/Fs, so
% this matches it. The difference is half a millisecond either way.
s1Frames = runPeaks(states, post, 1, MIN_RUN);
s1Times  = centres(s1Frames) / TARGET_FS;

out.s1Times    = s1Times(:)';
out.frameTimes = centres / TARGET_FS;
out.states     = states(:)';
out.posteriors = post;
out.confidence = mean(max(post, [], 2));

if numel(s1Times) >= 2
    rr = diff(s1Times);
    out.bpm   = 60/mean(rr);
    out.bpmCV = std(rr)/mean(rr);
else
    out.bpm = NaN; out.bpmCV = NaN;
end

out.stateMedians = zeros(1,4);
for s = 1:4
    d = runLengths(states, s) * HOP / TARGET_FS * 1000;
    if ~isempty(d), out.stateMedians(s) = median(d); end
end

if nargout > 1
    % Every intermediate, so a port can be checked one stage at a time
    % instead of only at the end.
    dbg.resampled    = audio(:)';
    dbg.afterBandpass= xFilt(:)';
    dbg.percentile995= scale;
    dbg.afterNormalise = x(:)';
    dbg.frameCentres = centres(:)';
    dbg.posteriors   = post;
    dbg.decodedStates= states(:)';
    k = min(4000, max(1, N - WIN));
    dbg.singleWindow = struct('startIndex1Based', k, ...
                              'input', x(k:k+WIN-1)', ...
                              'output', forwardPass(x(k:k+WIN-1), M));
end
end


%% ====================================================================
%  NETWORK FORWARD PASS
%  ====================================================================
function p = forwardPass(win, M)
%FORWARDPASS  One 4096-sample window -> 4 probabilities.
% Activation is [time x channels] throughout.
a = win(:);
for k = 1:numel(M.layers)
    % jsondecode gives a cell array when the layer records have differing
    % fields, and a struct array when they happen to match. Handle both.
    if iscell(M.layers), L = M.layers{k}; else, L = M.layers(k); end
    switch L.type
        case 'input'
            % nothing to do
        case 'conv1d'
            a = conv1dSame(a, L);
        case 'batchnorm'
            a = (a - L.mean(:)') ./ sqrt(L.variance(:)' + L.epsilon) ...
                .* L.scale(:)' + L.offset(:)';
        case 'relu'
            a = max(a, 0);
        case 'maxpool1d'
            a = maxPool(a, L.pool, L.stride);
        case 'flatten'
            % MATLAB column-major over [time x 1 x channel]: all times for
            % channel 1, then all times for channel 2, and so on.
            a = a(:);
        case 'dropout'
            % inference only - identity. Do not implement.
        case 'dense'
            W = reshape(L.weights(:), L.inputs, L.outputs)';   % [out x in]
            a = W*a(:) + L.bias(:);
        case 'softmax'
            a = a - max(a);
            a = exp(a); a = a/sum(a);
        otherwise
            error('Unknown layer type %s', L.type);
    end
end
p = a(:)';
end

function y = conv1dSame(x, L)
%CONV1DSAME  Cross-correlation (no kernel flip), zero padding, stride 1.
k    = L.kernel;  Cin = L.inChannels;  Cout = L.outChannels;
W    = reshape(L.weights(:), k, Cin, Cout);
T    = size(x,1);
pad  = (k-1)/2;                       % every kernel here is odd
xp   = [zeros(pad,Cin); x; zeros(pad,Cin)];
y    = zeros(T, Cout);
for co = 1:Cout
    acc = zeros(T,1);
    for ci = 1:Cin
        for j = 1:k
            acc = acc + W(j,ci,co) * xp(j : j+T-1, ci);
        end
    end
    y(:,co) = acc + L.bias(co);
end
end

function y = maxPool(x, pool, stride)
T  = size(x,1);
nO = floor((T - pool)/stride) + 1;
y  = zeros(nO, size(x,2));
for i = 1:nO
    s = (i-1)*stride + 1;
    y(i,:) = max(x(s : s+pool-1, :), [], 1);
end
end


%% ====================================================================
%  VITERBI
%  ====================================================================
function path = decodeViterbi(post, pStay)
[nF, nS] = size(post);
logE  = log(max(post, 1e-12));
logS  = log(max(pStay, 1e-6));
logA  = log(max(1-pStay, 1e-6));
prevOf = [4 1 2 3];

delta = -inf(nF, nS);
psi   = zeros(nF, nS);
delta(1,:) = log(1/nS) + logE(1,:);

for t = 2:nF
    for s = 1:nS
        stay = delta(t-1,s)          + logS(s);
        p    = prevOf(s);
        move = delta(t-1,p)          + logA(p);
        if stay >= move
            delta(t,s) = stay + logE(t,s);  psi(t,s) = s;
        else
            delta(t,s) = move + logE(t,s);  psi(t,s) = p;
        end
    end
end

path = zeros(nF,1);
[~, path(nF)] = max(delta(nF,:));
for t = nF-1:-1:1
    path(t) = psi(t+1, path(t+1));
end
end


%% ====================================================================
%  SMALL HELPERS  (replaced by library calls in most languages)
%  ====================================================================
function f = runPeaks(states, post, code, minRun)
m  = states(:) == code;
d  = diff([0; m; 0]);
st = find(d==1); en = find(d==-1)-1;
keep = (en-st+1) >= minRun;
st = st(keep); en = en(keep);
f = zeros(numel(st),1);
for i = 1:numel(st)
    seg = st(i):en(i);
    [~, j] = max(post(seg, code));
    f(i) = seg(j);
end
end

function d = runLengths(states, code)
m  = states(:) == code;
dd = diff([0; m; 0]);
d  = find(dd==-1) - find(dd==1);
end

function y = filtfiltSimple(b, a, x)
%FILTFILTSIMPLE  Zero-phase: forward, reverse, forward, reverse.
% MATLAB's filtfilt adds edge-transient handling; this does not. The
% difference is confined to the first and last ~30 samples and does not
% move any detection. Port THIS version so results match exactly.
y = filter(b, a, x);
y = flipud(y);
y = filter(b, a, y);
y = flipud(y);
end

function v = prctileSimple(x, p)
s = sort(x(:));
n = numel(s);
if n == 0, v = 0; return; end
% MATLAB's prctile convention
pos = p/100*n + 0.5;
if pos <= 1,      v = s(1);
elseif pos >= n,  v = s(n);
else
    lo = floor(pos); frac = pos - lo;
    v = s(lo)*(1-frac) + s(lo+1)*frac;
end
end

function [b, a] = localButterCoeffs()
%LOCALBUTTERCOEFFS  4th-order Butterworth bandpass, 25-400 Hz at 2000 Hz.
% Hardcoded so no filter-design toolbox is needed. Regenerate with:
%   [b,a] = butter(4, [25 400]/1000, 'bandpass');
b = [0.0379730414194768, 0, -0.151892165677907, 0, 0.227838248516861, ...
     0, -0.151892165677907, 0, 0.0379730414194768];
a = [1, -4.70203268042376, 9.6538853799703, -11.6429927241205, ...
     9.30055661969788, -5.07209079812917, 1.79598268938372, ...
     -0.371013433549363, 0.0377338824211926];
end
