function out = classifyMurmur(x, Fs, seg, model)
%CLASSIFYMURMUR  Murmur / no-murmur score from audio plus beat boundaries.
%
%   out = classifyMurmur(x, Fs, seg, model)
%
% INPUTS
%   x      Nx1 audio, ALREADY bandpass filtered 25-400 Hz and amplitude
%          normalized -- exactly the signal segmentHeartSound.m works on.
%          Do not pass raw audio.
%   Fs     sample rate in Hz (2000 for the recordings this was trained on)
%   seg    Mx5 beat boundaries from the segmenter, one row per complete
%          heartbeat, in SAMPLES:
%             [s1start  sysStart  s2start  diaStart  diaEnd]
%          so S1 runs s1start..sysStart-1, systole sysStart..s2start-1,
%          S2 s2start..diaStart-1 and diastole diaStart..diaEnd-1.
%   model  struct decoded from murmurModel.json
%
% OUTPUT
%   out.ok        false when the recording cannot be measured. On the device
%                 this is the "unable to analyze" outcome, not a negative.
%   out.score     0..1, probability of a murmur
%   out.isMurmur  out.score >= model.threshold
%   out.features  1x4 raw feature values, before transform and scaling
%   out.nBeats    beats that contributed
%
% DEPENDENCIES: none. No toolboxes. The only non-trivial primitive is a
% real-input FFT, which a Kotlin port must supply; everything else is
% arithmetic. See README.md.
%
% This file is the reference. If the port and this disagree, this is right.

out = struct('ok',false, 'score',NaN, 'isMurmur',false, ...
             'features',nan(1,4), 'nBeats',0);

x = double(x(:));
N = numel(x);
if isempty(seg) || size(seg,2) ~= 5, return; end

nB  = size(seg,1);
r1  = nan(nB,1);     % systolic flatness / S1 flatness
r2  = nan(nB,1);     % diastolic flatness / S1 flatness
occ = nan(nB,1);     % share of systole louder than that beat's diastole

for i = 1:nB
    s1 = cutSegment(x, seg(i,1), seg(i,2), N);
    sy = cutSegment(x, seg(i,2), seg(i,3), N);
    di = cutSegment(x, seg(i,4), seg(i,5), N);
    if isempty(s1) || isempty(sy) || isempty(di), continue; end

    f1 = spectralFlatness(s1, Fs, model.bandLow, model.bandHigh);
    fs = spectralFlatness(sy, Fs, model.bandLow, model.bandHigh);
    fd = spectralFlatness(di, Fs, model.bandLow, model.bandHigh);

    r1(i) = fs / (f1 + eps);
    r2(i) = fd / (f1 + eps);
    occ(i) = occupancy(sy, di, model.occupancyBins);
end

envCorr = beatConsistency(x, seg(:,[2 3]), N, ...
                          model.envBins, model.envTrimFraction);

f = [medianOmitNaN(r2), medianOmitNaN(occ), ...
     medianOmitNaN(r1), envCorr];
out.features = f;
out.nBeats   = sum(isfinite(r1));

if any(~isfinite(f)), return; end

% ---- the model -------------------------------------------------------
% z = intercept + sum_k  w_k * (t_k(f_k) - center_k) / scale_k
% where t_k is log for the two ratio features and identity for the other
% two, which are already bounded and dimensionless.
z = model.intercept;
for k = 1:numel(f)
    v = f(k);
    if model.logTransform(k), v = log(max(v, eps)); end
    z = z + model.weight(k) * (v - model.center(k)) / model.scale(k);
end

out.score    = 1 / (1 + exp(-z));
out.isMurmur = out.score >= model.threshold;
out.ok       = true;
end


%% ===================== segment helpers =====================
function s = cutSegment(x, a, b, N)
%CUTSEGMENT  x(a .. b-1), clipped, empty when shorter than 129 samples.
a = max(1, round(a));
b = min(N, round(b) - 1);
if b - a < 128, s = []; else, s = x(a:b); end
end

function m = medianOmitNaN(v)
v = v(isfinite(v));
if isempty(v), m = NaN; else, m = median(v); end
end


%% ===================== feature 1 and 3: flatness =====================
function v = spectralFlatness(s, Fs, lo, hi)
%SPECTRALFLATNESS  Geometric mean of the power spectrum over its arithmetic
%mean, measured only between lo and hi Hz.
%
% Near 1 means every frequency carries similar power, which is noise and
% therefore turbulent flow. Near 0 means the power sits in a few peaks,
% which is what a valve impact looks like.
%
% Only the band matters. Outside it the signal is the bandpass filter's
% stopband, and because the geometric mean is a mean of logarithms those
% near-zero bins would dominate it completely.
%
% The PSD's overall SCALE cancels in the ratio of the two means, so a port
% does not need to reproduce any particular normalization -- only the same
% window, overlap, transform length and frequency bins.

v = NaN;
s = s(:);
n = numel(s);
if n < 128, return; end

w = 2^floor(log2(n/4));
w = min(max(w, 64), 512);
if n < 2*w, return; end
nfft = max(512, 2*w);

p = welchPSD(s - mean(s), w, round(w/2), nfft);
f = (0:floor(nfft/2))' * (Fs / nfft);

p = p(f >= lo & f <= hi);
p = p(isfinite(p) & p > 0);
if numel(p) < 8, return; end

v = exp(mean(log(p))) / mean(p);
end

function p = welchPSD(s, w, noverlap, nfft)
%WELCHPSD  Averaged one-sided periodogram. Hann window, 50% overlap.
%
% Averaging several short transforms instead of taking one long one matters
% here: the bins of a single periodogram are exponentially distributed
% whatever the signal, which would put a large random term into the
% geometric mean. Systole is only a few hundred samples long.
%
% Returns floor(nfft/2)+1 values. Unnormalized -- see spectralFlatness.
n    = numel(s);
step = w - noverlap;
nSeg = floor((n - noverlap) / step);
if nSeg < 1, p = zeros(floor(nfft/2)+1, 1); return; end

win = hannWindow(w);
acc = zeros(floor(nfft/2)+1, 1);
for k = 0:nSeg-1
    a = k*step + 1;
    seg = s(a : a+w-1) .* win;
    S = fft(seg, nfft);
    S = S(1:floor(nfft/2)+1);
    acc = acc + abs(S).^2;
end
p = acc / nSeg;
end

function w = hannWindow(n)
%HANNWINDOW  Symmetric Hann, matching MATLAB's hann(n) default.
if n == 1, w = 1; return; end
w = 0.5 * (1 - cos(2*pi*(0:n-1)'/(n-1)));
end


%% ===================== feature 2: occupancy =====================
function o = occupancy(sy, di, K)
%OCCUPANCY  Share of systole louder than that beat's own diastole.
%
% Systole is split into K equal bins and each bin's RMS compared against the
% RMS of diastole in the SAME beat, so no gain or calibration enters. A
% holosystolic murmur fills the interval and scores near 1; a brief
% mid-systolic one scores lower; a silent systole scores near 0.
o = NaN;
if isempty(sy) || isempty(di) || numel(sy) < 4*K, return; end
ref = sqrt(mean(di.^2));
if ~isfinite(ref) || ref <= 0, return; end

idx = round(linspace(1, numel(sy)+1, K+1));
hit = 0;
for k = 1:K
    b = sy(idx(k):idx(k+1)-1);
    hit = hit + (sqrt(mean(b.^2)) > ref);
end
o = hit / K;
end


%% ===================== feature 4: beat consistency =====================
function c = beatConsistency(x, ab, N, K, trimFrac)
%BEATCONSISTENCY  Does systole's envelope repeat from one beat to the next?
%
% Each systole is trimmed at both ends, reduced to K log-RMS bins, and
% correlated against every other beat's. The median pairwise correlation is
% returned.
%
% The trim matters. Systole starts just after S1 and ends just before S2, so
% its first and last samples carry the tail of one and the onset of the
% other. Those arrive identically on every beat and would produce a high
% correlation for every recording, including silent ones.
c = NaN;
nB = size(ab,1);
if nB < 3, return; end

E = nan(nB, K);
for i = 1:nB
    a = max(1, round(ab(i,1)));
    b = min(N, round(ab(i,2)) - 1);
    L = b - a;
    if L < 4*K, continue; end
    t = round(trimFrac * L);
    s = x(a+t : b-t);
    if numel(s) < 2*K, continue; end
    E(i,:) = binLogRMS(s, K);
end

good = all(isfinite(E), 2) & std(E, 0, 2) > 0;
E = E(good, :);
m = size(E,1);
if m < 3, return; end

vals = [];
for i = 1:m-1
    for j = i+1:m
        vals(end+1) = pearson(E(i,:), E(j,:)); %#ok<AGROW>
    end
end
c = medianOmitNaN(vals(:));
end

function e = binLogRMS(s, K)
n   = numel(s);
idx = round(linspace(1, n+1, K+1));
e   = zeros(1, K);
for k = 1:K
    b = s(idx(k):idx(k+1)-1);
    e(k) = log(sqrt(mean(b.^2)) + eps);
end
end

function r = pearson(a, b)
a = a(:) - mean(a);
b = b(:) - mean(b);
d = sqrt(sum(a.^2) * sum(b.^2));
if d <= 0, r = NaN; else, r = sum(a.*b) / d; end
end
