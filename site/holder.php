<?php
// The holder check of the dark room page (darkroom.html).
//
// The page sends three things: a Solana wallet address, a short message the wallet signed, and the signature. This
// script checks that the message is ours and recent, that the signature was made by that wallet, and that the wallet
// holds some of the coin. It answers with one word and stores nothing: no file, no log line of its own.
//
// It runs here and not in the visitor's browser because Solana's public node refuses requests that come from a web
// page (tested 2026-11-19: 403 with a browser Origin, "ok" without one). So this server, not the visitor, asks the
// node, and the node sees this server's address and the wallet address.
//
// It is a soft lock: the page it opens runs in the visitor's browser, and the model it loads is public on Hugging Face.
declare(strict_types=1);

const MINT   = '7pZ5HMPTFQsLP6D8Zg6VfgjbKRycitG3H4Dq7cmBpump';   // the coin: the address shown on the home page
const NODES  = ['https://api.mainnet-beta.solana.com'];
const WINDOW = 600;                                              // a signed message is accepted for ten minutes
const B58    = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

function answer(string $state, int $code = 200): void {
    http_response_code($code);
    header('Content-Type: application/json');
    header('Cache-Control: no-store');
    echo json_encode(['state' => $state]), "\n";
    exit;
}

// A Solana address is 32 bytes written in base58.
function b58decode(string $text): ?string {
    $bytes = [];
    $n = strlen($text);
    for ($i = 0; $i < $n; $i++) {
        $carry = strpos(B58, $text[$i]);
        if ($carry === false) {
            return null;
        }
        for ($j = 0, $m = count($bytes); $j < $m; $j++) {
            $carry += $bytes[$j] * 58;
            $bytes[$j] = $carry & 0xff;
            $carry >>= 8;
        }
        while ($carry > 0) {
            $bytes[] = $carry & 0xff;
            $carry >>= 8;
        }
    }
    for ($i = 0; $i < $n && $text[$i] === '1'; $i++) {
        $bytes[] = 0;
    }
    return implode('', array_map('chr', array_reverse($bytes)));
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    answer('post-only', 405);
}
$in = json_decode((string) file_get_contents('php://input', false, null, 0, 4096), true);
$address   = is_array($in) && is_string($in['address'] ?? null) ? $in['address'] : '';
$message   = is_array($in) && is_string($in['message'] ?? null) ? $in['message'] : '';
$signature = is_array($in) && is_string($in['signature'] ?? null) ? base64_decode($in['signature'], true) : false;

if (!preg_match('/^[1-9A-HJ-NP-Za-km-z]{32,44}$/', $address) || strlen($message) > 600 || $signature === false || strlen($signature) !== 64) {
    answer('bad-request', 400);
}

// The message has to be the one the page writes: it names the wallet and carries the time it was signed.
$shape = '/\ADarkProof dark room\nWallet: ([1-9A-HJ-NP-Za-km-z]{32,44})\nTime: (\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z)\n/';
if (!preg_match($shape, $message, $m) || $m[1] !== $address) {
    answer('bad-message', 400);
}
$signedAt = strtotime($m[2]);
if ($signedAt === false || abs(time() - $signedAt) > WINDOW) {
    answer('stale', 400);
}

$key = b58decode($address);
if ($key === null || strlen($key) !== 32) {
    answer('bad-request', 400);
}
if (!function_exists('sodium_crypto_sign_verify_detached')) {
    answer('no-sodium', 500);
}
if (!sodium_crypto_sign_verify_detached($signature, $message, $key)) {
    answer('bad-signature', 403);
}

// The wallet is theirs. Does it hold the coin? Any amount above zero counts.
$ask = json_encode([
    'jsonrpc' => '2.0',
    'id'      => 1,
    'method'  => 'getTokenAccountsByOwner',
    'params'  => [$address, ['mint' => MINT], ['encoding' => 'jsonParsed', 'commitment' => 'confirmed']],
]);
foreach (NODES as $node) {
    $ch = curl_init($node);
    curl_setopt_array($ch, [
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => $ask,
        CURLOPT_HTTPHEADER     => ['Content-Type: application/json'],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_TIMEOUT        => 15,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_USERAGENT      => 'holder-check/1',
    ]);
    $body = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    if (!is_string($body) || $code !== 200) {
        continue;
    }
    $data = json_decode($body, true);
    if (!is_array($data) || !isset($data['result']['value']) || !is_array($data['result']['value'])) {
        continue;
    }
    foreach ($data['result']['value'] as $account) {
        $amount = $account['account']['data']['parsed']['info']['tokenAmount']['amount'] ?? '0';
        if (is_string($amount) && ctype_digit($amount) && ltrim($amount, '0') !== '') {
            answer('holder');
        }
    }
    answer('not-holder');
}
answer('node-error', 502);
