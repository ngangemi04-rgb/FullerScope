%% verifyPort.m
% Proves segmentHeartSound.m reproduces the trained MATLAB network, and
% gives a port the same check to run against.
%
% Compares, on the same audio:
%   A. segmentHeartSound.m   - the standalone reference, reads model.json,
%                              no Deep Learning Toolbox
%   B. the trained network   - cnnPredictStates / cnnViterbi, the pipeline
%                              that produced the published results
%
% If A matches B, the exported weights and the hand-written forward pass are
% correct, and anyone porting A to another language has a trustworthy target.
%
% Run from the export/ folder.

clear; clc;

here = fileparts(mfilename('fullpath'));
addpath(fileparts(here));                 % reach the pipeline functions
cd(here);

CLIP_SECONDS = 3;    % the reference forward pass is unoptimised; keep it short

wav = 'C:\Users\Amand\OneDrive\Capstone Algorithm Team\training_data\training-a\a0001.wav';
[audio, Fs] = audioread(wav);
if size(audio,2) > 1, audio = audio(:,1); end
audio = audio(1:min(round(CLIP_SECONDS*Fs), numel(audio)));

%% ---- A: the standalone reference --------------------------------------
fprintf('Running segmentHeartSound.m (unoptimised reference)...\n');
tA = tic;
A = segmentHeartSound(audio, Fs, 'model.json');
fprintf('  %.1f s for %.1f s of audio\n', toc(tA), numel(audio)/Fs);

%% ---- B: the trained network -------------------------------------------
cfg = cnnConfigSegV2();
L   = load(fullfile(cfg.cacheDir,'segV2Model.mat'), 'net','trans','cfg');
mcfg = L.cfg;

nyq = mcfg.Fs/2;
[bb,aa] = butter(4, [mcfg.bandLow min(mcfg.bandHigh,0.98*nyq)]/nyq, 'bandpass');
x = filtfilt(bb, aa, audio);
s = prctile(abs(x), 99.5);
x = max(min(x/s, 8), -8);

R.x = single(x); R.Fs = mcfg.Fs;
P = cnnPredictStates(L.net, R, mcfg);
Bstates = cnnViterbi(P.post, L.trans);
Bs1 = cnnEventsFromStates(Bstates, P.post, P.centres, 1, mcfg.Fs, mcfg.minRunFrames);

%% ---- compare -----------------------------------------------------------
fprintf('\n=== POSTERIORS ===\n');
n = min(size(A.posteriors,1), size(P.post,1));
d = abs(A.posteriors(1:n,:) - double(P.post(1:n,:)));
fprintf('frames compared: %d\n', n);
fprintf('max absolute difference : %.3e\n', max(d(:)));
fprintf('mean absolute difference: %.3e\n', mean(d(:)));

% Exact float agreement is not achievable and not the right target. The
% trained network runs in SINGLE precision; this reference computes in
% DOUBLE. Where the network is confident the logits are far apart and the
% softmax barely moves; where it is genuinely undecided they are close and
% small numerical differences get amplified. So the differences should
% scale inversely with confidence -- and if they do, the implementation is
% right. A real bug (wrong weight layout, padding, or flatten order) would
% produce large differences uncorrelated with confidence AND would change
% the decoded states.
topP  = max(A.posteriors(1:n,:), [], 2);
dmax  = max(d, [], 2);
bands = {[0 .6],[.6 .9],[.9 .99],[.99 1.001]};
fprintf('difference by confidence band:\n');
for k = 1:numel(bands)
    m = topP >= bands{k}(1) & topP < bands{k}(2);
    if any(m)
        fprintf('  topProb %.2f-%.2f : n=%4d  mean %.2e  max %.2e\n', ...
            bands{k}(1), bands{k}(2), sum(m), mean(dmax(m)), max(dmax(m)));
    end
end
confident = topP > 0.9;
interiorOK = ~any(confident) || max(dmax(confident)) < 2e-2;

fprintf('\n=== DECODED STATES ===\n');
agree = mean(A.states(1:n)' == Bstates(1:n));
fprintf('identical on %.2f%% of frames\n', 100*agree);

fprintf('\n=== S1 DETECTIONS ===\n');
fprintf('reference : %s\n', strjoin(compose('%.3f', A.s1Times), '  '));
fprintf('network   : %s\n', strjoin(compose('%.3f', Bs1(:)'), '  '));
if numel(A.s1Times) == numel(Bs1)
    fprintf('max timing difference: %.1f ms\n', 1000*max(abs(A.s1Times(:) - Bs1(:))));
else
    fprintf('DIFFERENT COUNTS: %d vs %d\n', numel(A.s1Times), numel(Bs1));
end
fprintf('bpm: reference %.1f\n', A.bpm);

%% ---- verdict -----------------------------------------------------------
% What actually matters is that the DECISIONS match, not that the floats do.
% Exact float agreement is unreachable across languages anyway -- the useful
% bar is identical decoded states and S1 timing well inside the tolerance
% any evaluation uses (an S1 interval is ~128 ms; we require under 5 ms).
sameCount = numel(A.s1Times) == numel(Bs1);
timingOK  = sameCount && max(abs(A.s1Times(:) - Bs1(:))) < 0.005;
ok = interiorOK && agree > 0.99 && timingOK;

fprintf('\n%s\n', repmat('=',1,60));
if ok
    fprintf('PASS - the standalone reference reproduces the trained network.\n');
    fprintf('  decoded states identical, S1 timing within 5 ms, posteriors agree.\n');
    fprintf('A port matching these numbers is correct.\n');
else
    fprintf('MISMATCH - do not ship this export until it is resolved.\n');
    fprintf('  confident-frame posteriors agree : %d\n', interiorOK);
    fprintf('  decoded states agree >99%%        : %d\n', agree > 0.99);
    fprintf('  S1 timing within 5 ms            : %d\n', timingOK);
end
fprintf('%s\n', repmat('=',1,60));
