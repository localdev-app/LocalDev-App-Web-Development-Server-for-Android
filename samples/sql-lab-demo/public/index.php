<?php
require dirname(__DIR__) . '/config.php';
try {
    $rows = db()->query('SELECT id, name, downloads FROM apks ORDER BY id')->fetchAll();
} catch (Throwable $e) {
    http_response_code(500);
    echo '<h2>Database belum siap</h2><pre>' . htmlspecialchars($e->getMessage()) . '</pre>';
    exit;
}
?><!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>LocalDev SQL Lab</title>
<style>body{font-family:system-ui;background:#071018;color:#eef;padding:24px}.card{background:#10202a;padding:14px;margin:10px 0;border-radius:14px}b{color:#12e4e4}</style></head>
<body><h1>LocalDev SQL Lab ✅</h1><p>PDO + database lokal berhasil.</p>
<?php foreach ($rows as $row): ?><div class="card"><b><?= htmlspecialchars($row['name']) ?></b> — <?= (int)$row['downloads'] ?> downloads</div><?php endforeach; ?>
</body></html>
