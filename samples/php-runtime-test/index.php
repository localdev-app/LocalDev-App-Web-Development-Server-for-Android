<?php
header('Content-Type: text/html; charset=UTF-8');

$checks = [
    'PDO SQLite' => extension_loaded('pdo_sqlite'),
    'SQLite3' => extension_loaded('sqlite3'),
    'cURL' => extension_loaded('curl'),
    'OpenSSL' => extension_loaded('openssl'),
    'ZIP' => extension_loaded('zip'),
];

$dbPath = __DIR__ . '/demo.sqlite';
$sqliteMessage = 'Belum dites';
try {
    $db = new PDO('sqlite:' . $dbPath);
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $db->exec('CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, text TEXT NOT NULL)');
    $db->exec("INSERT INTO notes(text) VALUES ('LocalDev OK')");
    $count = (int)$db->query('SELECT COUNT(*) FROM notes')->fetchColumn();
    $sqliteMessage = "SQLite write/read OK — rows: {$count}";
} catch (Throwable $e) {
    $sqliteMessage = 'SQLite ERROR: ' . $e->getMessage();
}

$opensslMessage = function_exists('openssl_random_pseudo_bytes')
    ? bin2hex(openssl_random_pseudo_bytes(8))
    : 'OpenSSL function tidak tersedia';

$zipMessage = 'ZIP belum tersedia';
if (class_exists('ZipArchive')) {
    $zipPath = __DIR__ . '/demo.zip';
    $z = new ZipArchive();
    if ($z->open($zipPath, ZipArchive::CREATE | ZipArchive::OVERWRITE) === true) {
        $z->addFromString('hello.txt', 'LocalDev ZIP OK');
        $z->close();
        $zipMessage = 'ZipArchive create OK';
    }
}
?>
<!doctype html>
<html lang="id">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LocalDev Runtime Test</title>
<style>
body{font-family:system-ui;background:#101114;color:#eee;padding:22px} .card{background:#191b20;border-radius:16px;padding:18px;margin:12px 0} .ok{color:#5ee18a}.bad{color:#ff6b6b} code{word-break:break-all}
</style>
</head>
<body>
<h1>LocalDev ✅</h1>
<div class="card">PHP <b><?= htmlspecialchars(PHP_VERSION) ?></b><br>Server: <code><?= htmlspecialchars($_SERVER['SERVER_SOFTWARE'] ?? 'unknown') ?></code></div>
<div class="card">
<?php foreach ($checks as $name => $ok): ?>
<div class="<?= $ok ? 'ok' : 'bad' ?>"><?= $ok ? '✓' : '✗' ?> <?= htmlspecialchars($name) ?></div>
<?php endforeach; ?>
</div>
<div class="card"><?= htmlspecialchars($sqliteMessage) ?></div>
<div class="card">OpenSSL random: <code><?= htmlspecialchars($opensslMessage) ?></code></div>
<div class="card"><?= htmlspecialchars($zipMessage) ?></div>
</body></html>
