<?php
// Keeps outages.json fresh: IODA's summary of internet outages by country for the last 24 hours, in the short form
// the outages page reads. Visitors never contact IODA; only this script does, from the server.
//
// It runs two ways. A scheduled job can call it (php outages-refresh.php). Without one, the outages page asks for
// it when the copy it got is over an hour old. Either way it fetches at most once an hour, and after a failure it
// waits ten minutes before it asks the source again, however many visitors call it.
//
// The same short form is written by make-outages.py (the copy that ships with a deploy). Keep the two in step.
declare(strict_types=1);

const TARGET  = __DIR__ . '/outages.json';
const STAMP   = __DIR__ . '/outages.try';   // when the source was last asked
const MAX_AGE = 3600;
const RETRY   = 600;
const SOURCE  = 'https://api.ioda.inetintel.cc.gatech.edu/v2/outages/summary';

function answer(string $state): void {
    if (PHP_SAPI !== 'cli') {
        header('Content-Type: application/json');
        header('Cache-Control: no-store');
    }
    echo json_encode(['state' => $state]), "\n";
    exit;
}

$now = time();

$old = is_file(TARGET) ? json_decode((string) file_get_contents(TARGET), true) : null;
if (is_array($old) && isset($old['updated']) && $now - (int) $old['updated'] < MAX_AGE) {
    answer('fresh');
}

// One caller at a time, and not too often.
$lock = fopen(STAMP, 'c+');
if ($lock === false || !flock($lock, LOCK_EX | LOCK_NB)) {
    answer('busy');
}
$last = (int) stream_get_contents($lock);
if ($now - $last < RETRY) {
    answer('waiting');
}
ftruncate($lock, 0);
rewind($lock);
fwrite($lock, (string) $now);
fflush($lock);

$from = $now - 86400;
$ch = curl_init(SOURCE . '?entityType=country&from=' . $from . '&until=' . $now . '&limit=400');
curl_setopt_array($ch, [
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_CONNECTTIMEOUT => 10,
    CURLOPT_TIMEOUT        => 25,
    CURLOPT_FOLLOWLOCATION => false,
    CURLOPT_USERAGENT      => 'outage-map-cache/1',
]);
$body = curl_exec($ch);
$code = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
curl_close($ch);
if (!is_string($body) || $code !== 200) {
    answer('source-error');
}

$data = json_decode($body, true);
if (!is_array($data) || !isset($data['data']) || !is_array($data['data'])) {
    answer('source-error');
}

$rows = [];
foreach ($data['data'] as $item) {
    $c = $item['entity']['code'] ?? null;
    $n = $item['entity']['name'] ?? null;
    $s = $item['scores']['overall'] ?? null;
    if (!is_string($c) || !preg_match('/^[A-Za-z]{2}$/', $c) || !is_string($n) || strlen($n) > 120 || !is_numeric($s)) {
        continue;
    }
    $rows[] = [strtoupper($c), $n, (int) round((float) $s), (int) ($item['event_cnt'] ?? 0)];
}
usort($rows, static fn(array $a, array $b): int => $b[2] <=> $a[2]);

$out = json_encode([
    'v'       => 1,
    'updated' => $now,
    'from'    => $from,
    'until'   => $now,
    'source'  => 'IODA, Georgia Institute of Technology',
    'rows'    => $rows,
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

$tmp = TARGET . '.tmp';
if ($out === false || file_put_contents($tmp, $out . "\n", LOCK_EX) === false || !rename($tmp, TARGET)) {
    answer('write-error');
}
answer('updated');
