function verifyPort()
%VERIFYPORT  Check an implementation against the shipped test vectors.
%
%   verifyPort
%
% Run this first in MATLAB to confirm the package is intact. It should pass
% at machine precision, because it is checking the reference against vectors
% the reference produced.
%
% Its real purpose is as a specification. A Kotlin port passes when it
% reproduces the same three stages on the same inputs:
%
%   1. flatnessCases   three short signals -> one flatness value each.
%                      No audio, no segmentation, no model. If these fail,
%                      the FFT or the windowing is wrong and nothing else
%                      will work.
%
%   2. recordings      audio plus beat boundaries -> four features, a score
%                      and a decision. The full path.
%
%   3. featureOnly     four features -> score. Pure arithmetic, no signal
%                      processing. If stage 2 fails but this passes, the
%                      problem is in feature extraction, not the model.
%
% Staged deliberately: when a port disagrees, these localize the fault to
% one of three places instead of leaving it somewhere in the whole chain.

here = fileparts(mfilename('fullpath'));
model = jsondecode(fileread(fullfile(here,'murmurModel.json')));
tv    = jsondecode(fileread(fullfile(here,'testvectors.json')));

TOL_FEATURE = 1e-6;
TOL_SCORE   = 1e-6;
fails = 0;

fprintf('\n%s\n', repmat('=',1,70));
fprintf('STAGE 1  flatness, no audio needed\n');
fprintf('%s\n', repmat('=',1,70));
for k = 1:numel(tv.flatnessCases)
    c = tv.flatnessCases(k);
    got = refFlatness(c.signal(:), model.sampleRate, model.bandLow, model.bandHigh);
    d   = abs(got - c.expected);
    ok  = d < TOL_FEATURE;
    fails = fails + ~ok;
    fprintf('  %-18s expected %.6f  got %.6f   %s\n', ...
            c.label, c.expected, got, pf(ok));
end

fprintf('\n%s\n', repmat('=',1,70));
fprintf('STAGE 2  audio plus boundaries -> score\n');
fprintf('%s\n', repmat('=',1,70));
fprintf('  %-10s %9s %9s %9s %9s %8s %6s\n', 'recording', ...
        'diaFlat', 'sysOccup', 'sysFlat', 'envCorr', 'score', '');
for k = 1:numel(tv.recordings)
    r = tv.recordings(k);
    o = classifyMurmur(r.audio(:), r.sampleRate, r.seg, model);
    df = max(abs(o.features - r.features(:)') ./ max(abs(r.features(:)'),1e-6));
    ds = abs(o.score - r.score);
    ok = o.ok && df < TOL_FEATURE && ds < TOL_SCORE && o.isMurmur == r.isMurmur;
    fails = fails + ~ok;
    fprintf('  %-10s %9.4f %9.4f %9.4f %9.4f %8.4f %6s\n', ...
            r.name, o.features(1), o.features(2), o.features(3), o.features(4), ...
            o.score, pf(ok));
end

fprintf('\n%s\n', repmat('=',1,70));
fprintf('STAGE 3  features -> score, arithmetic only\n');
fprintf('%s\n', repmat('=',1,70));
worst = 0;
for k = 1:numel(tv.featureOnly)
    c = tv.featureOnly(k);
    z = model.intercept;
    for j = 1:numel(c.features)
        v = c.features(j);
        if model.logTransform(j), v = log(max(v, eps)); end
        z = z + model.weight(j) * (v - model.center(j)) / model.scale(j);
    end
    s = 1/(1+exp(-z));
    worst = max(worst, abs(s - c.score));
    fails = fails + (abs(s - c.score) >= TOL_SCORE) ...
                  + ((s >= model.threshold) ~= c.isMurmur);
end
fprintf('  %d cases, largest score difference %.3g   %s\n', ...
        numel(tv.featureOnly), worst, pf(worst < TOL_SCORE));

fprintf('\n%s\n', repmat('=',1,70));
if fails == 0
    fprintf('ALL CHECKS PASSED\n');
else
    fprintf('%d CHECK(S) FAILED\n', fails);
end
fprintf('%s\n\n', repmat('=',1,70));
end


function s = pf(ok)
if ok, s = 'pass'; else, s = 'FAIL'; end
end

function v = refFlatness(s, Fs, lo, hi)
% Same path classifyMurmur uses, exposed so stage 1 can run without
% boundaries or a model.
n = numel(s);
w = min(max(2^floor(log2(n/4)),64),512);
nfft = max(512, 2*w);
noverlap = round(w/2);
step = w - noverlap;
nSeg = floor((n - noverlap)/step);
win  = 0.5*(1-cos(2*pi*(0:w-1)'/(w-1)));
acc  = zeros(floor(nfft/2)+1,1);
sc   = s - mean(s);
for k = 0:nSeg-1
    a = k*step+1;
    S = fft(sc(a:a+w-1).*win, nfft);
    acc = acc + abs(S(1:floor(nfft/2)+1)).^2;
end
p = acc/nSeg;
f = (0:floor(nfft/2))'*(Fs/nfft);
p = p(f>=lo & f<=hi);
p = p(isfinite(p) & p>0);
v = exp(mean(log(p)))/mean(p);
end
