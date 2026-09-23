<?php
function db(): PDO {
    $dsn = sprintf('mysql:host=%s;dbname=%s;charset=%s', '127.0.0.1', 'localdev_demo', 'utf8mb4');
    return new PDO($dsn, 'root', '', [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::MYSQL_ATTR_INIT_COMMAND => 'SET NAMES utf8mb4',
    ]);
}
