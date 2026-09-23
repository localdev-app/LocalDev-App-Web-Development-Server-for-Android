<?php
/**
 * LocalDev PHP CLI bridge v0.6
 *
 * PMMP's Android PHP build runs reliably in CLI mode, while its pmmpthread
 * extension rejects cli-server. LocalDev therefore serves HTTP in Kotlin and
 * executes PHP requests through this bridge.
 */

$__ldScript = getenv('LOCALDEV_SCRIPT') ?: '';
$__ldDocumentRoot = getenv('LOCALDEV_DOCUMENT_ROOT') ?: dirname($__ldScript);
$__ldRequestMethod = getenv('LOCALDEV_REQUEST_METHOD') ?: 'GET';
$__ldRequestUri = getenv('LOCALDEV_REQUEST_URI') ?: '/';
$__ldQueryString = getenv('LOCALDEV_QUERY_STRING') ?: '';
$__ldScriptName = getenv('LOCALDEV_SCRIPT_NAME') ?: '/' . basename($__ldScript);
$__ldBodyFile = getenv('LOCALDEV_BODY_FILE') ?: '';
$__ldSessionDir = getenv('LOCALDEV_SESSION_DIR') ?: sys_get_temp_dir();
$__ldForcedSessionName = getenv('LOCALDEV_FORCED_SESSION_NAME') ?: 'PHPSESSID';
$__ldForcedSessionId = getenv('LOCALDEV_FORCED_SESSION_ID') ?: '';
$__ldHeadersB64 = getenv('LOCALDEV_HEADERS_B64') ?: '';
$__ldHeadersJson = $__ldHeadersB64 !== '' ? base64_decode($__ldHeadersB64, true) : '{}';
$__ldHeaders = is_string($__ldHeadersJson) ? json_decode($__ldHeadersJson, true) : [];
if (!is_array($__ldHeaders)) $__ldHeaders = [];

if (!is_file($__ldScript)) {
    echo "===LOCALDEV_RESPONSE===\nLOCALDEV_STATUS:404\nLOCALDEV_CONTENT_TYPE:text/plain; charset=UTF-8\n\nPHP entry file not found.";
    exit(0);
}

$_GET = [];
parse_str($__ldQueryString, $_GET);

$_COOKIE = [];
$__ldCookieHeader = $__ldHeaders['cookie'] ?? '';
if (is_string($__ldCookieHeader) && $__ldCookieHeader !== '') {
    foreach (explode(';', $__ldCookieHeader) as $__ldCookiePart) {
        $__ldCookiePart = trim($__ldCookiePart);
        if ($__ldCookiePart === '') continue;
        [$__ldCookieKey, $__ldCookieValue] = array_pad(explode('=', $__ldCookiePart, 2), 2, '');
        if ($__ldCookieKey !== '') {
            $_COOKIE[rawurldecode(trim($__ldCookieKey))] = rawurldecode(trim($__ldCookieValue));
        }
    }
}

// LocalDev's loopback server is single-user. If the browser drops the session cookie,
// Kotlin passes the last session id learned from the previous PHP request. Seed it here
// before the project's session_start() runs.
if (is_string($__ldForcedSessionId) && $__ldForcedSessionId !== '') {
    $__ldSafeName = preg_replace('/[^A-Za-z0-9_]/', '', (string)$__ldForcedSessionName) ?: 'PHPSESSID';
    $__ldSafeId = preg_replace('/[^A-Za-z0-9,-]/', '', (string)$__ldForcedSessionId);
    if ($__ldSafeId !== '') {
        $_COOKIE[$__ldSafeName] = $__ldSafeId;
    }
}

$__ldRawBody = ($__ldBodyFile !== '' && is_file($__ldBodyFile))
    ? (string) file_get_contents($__ldBodyFile)
    : '';

$_POST = [];
$__ldContentType = strtolower((string) ($__ldHeaders['content-type'] ?? ''));
if (str_starts_with($__ldContentType, 'application/x-www-form-urlencoded')) {
    parse_str($__ldRawBody, $_POST);
}
$_FILES = [];
$_REQUEST = array_merge($_COOKIE, $_GET, $_POST);

$_SERVER = array_merge($_SERVER, [
    'REQUEST_METHOD' => $__ldRequestMethod,
    'REQUEST_URI' => $__ldRequestUri,
    'QUERY_STRING' => $__ldQueryString,
    'DOCUMENT_ROOT' => $__ldDocumentRoot,
    'SCRIPT_FILENAME' => $__ldScript,
    'SCRIPT_NAME' => $__ldScriptName,
    'PHP_SELF' => $__ldScriptName,
    'SERVER_NAME' => '127.0.0.1',
    'SERVER_ADDR' => '127.0.0.1',
    'SERVER_PORT' => '8080',
    'SERVER_PROTOCOL' => 'HTTP/1.1',
    'SERVER_SOFTWARE' => 'LocalDev PHP Bridge/0.9.6',
    'REMOTE_ADDR' => '127.0.0.1',
    'REMOTE_PORT' => '0',
    'CONTENT_TYPE' => (string) ($__ldHeaders['content-type'] ?? ''),
    'CONTENT_LENGTH' => (string) strlen($__ldRawBody),
]);

foreach ($__ldHeaders as $__ldHeaderName => $__ldHeaderValue) {
    if (!is_string($__ldHeaderName) || !is_scalar($__ldHeaderValue)) continue;
    $__ldServerKey = 'HTTP_' . strtoupper(str_replace('-', '_', $__ldHeaderName));
    if ($__ldServerKey === 'HTTP_CONTENT_TYPE' || $__ldServerKey === 'HTTP_CONTENT_LENGTH') continue;
    $_SERVER[$__ldServerKey] = (string) $__ldHeaderValue;
}

$GLOBALS['LOCALDEV_RAW_BODY'] = $__ldRawBody;
$GLOBALS['LOCALDEV_REQUEST_HEADERS'] = $__ldHeaders;
$GLOBALS['__LOCALDEV_SESSION_DIR'] = $__ldSessionDir;
$GLOBALS['__LOCALDEV_SESSION_ACTIVE'] = false;
$GLOBALS['__LOCALDEV_SESSION_STARTED'] = false;
$GLOBALS['__LOCALDEV_SESSION_ID'] = '';
$GLOBALS['__LOCALDEV_SESSION_NAME'] = 'PHPSESSID';
$GLOBALS['__LOCALDEV_SESSION_INCOMING_ID'] = '';
$GLOBALS['__LOCALDEV_SESSION_COOKIE_DIRTY'] = false;

if (!function_exists('getallheaders')) {
    function getallheaders(): array {
        return $GLOBALS['LOCALDEV_REQUEST_HEADERS'] ?? [];
    }
}
if (!function_exists('apache_request_headers')) {
    function apache_request_headers(): array {
        return getallheaders();
    }
}
if (!function_exists('localdev_raw_input')) {
    function localdev_raw_input(): string {
        return (string) ($GLOBALS['LOCALDEV_RAW_BODY'] ?? '');
    }
}

/*
 * Some ARM64 CLI builds do not include ext/session. v0.5 assumed session_status()
 * always existed and crashed even when the user's own PHP did not use sessions.
 * When ext/session is missing, provide a small file-backed compatibility layer.
 */
if (!defined('PHP_SESSION_DISABLED')) define('PHP_SESSION_DISABLED', 0);
if (!defined('PHP_SESSION_NONE')) define('PHP_SESSION_NONE', 1);
if (!defined('PHP_SESSION_ACTIVE')) define('PHP_SESSION_ACTIVE', 2);

if (!function_exists('session_status')) {
    function __localdev_session_file(?string $id = null): string {
        $sid = $id ?? (string) ($GLOBALS['__LOCALDEV_SESSION_ID'] ?? '');
        $dir = (string) ($GLOBALS['__LOCALDEV_SESSION_DIR'] ?? sys_get_temp_dir());
        if (!is_dir($dir)) @mkdir($dir, 0700, true);
        return rtrim($dir, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR . 'sess_' . preg_replace('/[^A-Za-z0-9,-]/', '', $sid) . '.json';
    }

    function session_status(): int {
        return !empty($GLOBALS['__LOCALDEV_SESSION_ACTIVE']) ? PHP_SESSION_ACTIVE : PHP_SESSION_NONE;
    }

    function session_name(?string $name = null): string|false {
        if ($name !== null && $name !== '') {
            $GLOBALS['__LOCALDEV_SESSION_NAME'] = preg_replace('/[^A-Za-z0-9_]/', '', $name) ?: 'PHPSESSID';
        }
        return (string) ($GLOBALS['__LOCALDEV_SESSION_NAME'] ?? 'PHPSESSID');
    }

    function session_id(?string $id = null): string|false {
        if ($id !== null) {
            $GLOBALS['__LOCALDEV_SESSION_ID'] = preg_replace('/[^A-Za-z0-9,-]/', '', $id);
        }
        return (string) ($GLOBALS['__LOCALDEV_SESSION_ID'] ?? '');
    }

    function session_start(array $options = []): bool {
        global $_SESSION, $_COOKIE;
        if (session_status() === PHP_SESSION_ACTIVE) return true;
        $name = (string) session_name();
        $sid = isset($_COOKIE[$name]) ? (string) $_COOKIE[$name] : '';
        $sid = preg_replace('/[^A-Za-z0-9,-]/', '', $sid);
        if ($sid === '') $sid = bin2hex(random_bytes(16));
        $GLOBALS['__LOCALDEV_SESSION_ID'] = $sid;
        $GLOBALS['__LOCALDEV_SESSION_INCOMING_ID'] = isset($_COOKIE[$name]) ? preg_replace('/[^A-Za-z0-9,-]/', '', (string) $_COOKIE[$name]) : '';
        $GLOBALS['__LOCALDEV_SESSION_COOKIE_DIRTY'] = ($GLOBALS['__LOCALDEV_SESSION_INCOMING_ID'] === '');
        $file = __localdev_session_file($sid);
        $_SESSION = [];
        if (is_file($file)) {
            $decoded = json_decode((string) @file_get_contents($file), true);
            if (is_array($decoded)) $_SESSION = $decoded;
        }
        $GLOBALS['__LOCALDEV_SESSION_ACTIVE'] = true;
        $GLOBALS['__LOCALDEV_SESSION_STARTED'] = true;
        return true;
    }

    function session_write_close(): bool {
        global $_SESSION;
        if (session_status() !== PHP_SESSION_ACTIVE) return true;
        $sid = (string) session_id();
        if ($sid !== '') {
            @file_put_contents(__localdev_session_file($sid), json_encode($_SESSION ?? [], JSON_UNESCAPED_SLASHES), LOCK_EX);
        }
        $GLOBALS['__LOCALDEV_SESSION_ACTIVE'] = false;
        return true;
    }

    function session_destroy(): bool {
        global $_SESSION;
        $sid = (string) session_id();
        if ($sid !== '') @unlink(__localdev_session_file($sid));
        $_SESSION = [];
        $GLOBALS['__LOCALDEV_SESSION_ACTIVE'] = false;
        return true;
    }

    function session_regenerate_id(bool $delete_old_session = false): bool {
        global $_SESSION;
        if (session_status() !== PHP_SESSION_ACTIVE) return false;
        $old = (string) session_id();
        $new = bin2hex(random_bytes(16));
        $GLOBALS['__LOCALDEV_SESSION_ID'] = $new;
        $GLOBALS['__LOCALDEV_SESSION_COOKIE_DIRTY'] = true;
        @file_put_contents(__localdev_session_file($new), json_encode($_SESSION ?? [], JSON_UNESCAPED_SLASHES), LOCK_EX);
        if ($delete_old_session && $old !== '') @unlink(__localdev_session_file($old));
        return true;
    }

    if (!function_exists('session_unset')) {
        function session_unset(): bool {
            global $_SESSION;
            $_SESSION = [];
            return true;
        }
    }
    if (!function_exists('session_abort')) {
        function session_abort(): bool {
            $GLOBALS['__LOCALDEV_SESSION_ACTIVE'] = false;
            return true;
        }
    }
    if (!function_exists('session_get_cookie_params')) {
        function session_get_cookie_params(): array {
            return ['lifetime' => 0, 'path' => '/', 'domain' => '', 'secure' => false, 'httponly' => true, 'samesite' => 'Lax'];
        }
    }
    if (!function_exists('session_set_cookie_params')) {
        function session_set_cookie_params(array|int $lifetime_or_options, ?string $path = null, ?string $domain = null, ?bool $secure = null, ?bool $httponly = null): bool {
            return true;
        }
    }
}

$__ldFinished = false;
ob_start();

function __localdev_guess_content_type(string $body): string {
    $trimmed = ltrim($body);
    if ($trimmed === '') return 'text/html; charset=UTF-8';
    if ($trimmed[0] === '{' || $trimmed[0] === '[') return 'application/json; charset=UTF-8';
    if (str_starts_with($trimmed, '<?xml')) return 'application/xml; charset=UTF-8';
    return 'text/html; charset=UTF-8';
}

function __localdev_finish(): void {
    global $__ldFinished, $__ldCookieHeader;
    if ($__ldFinished) return;
    $__ldFinished = true;

    $body = ob_get_level() > 0 ? (string) ob_get_clean() : '';
    $status = http_response_code();
    if (!is_int($status) || $status < 100 || $status > 599) $status = 200;

    $fatal = error_get_last();
    if (is_array($fatal) && in_array($fatal['type'] ?? 0, [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR, E_USER_ERROR], true)) {
        if ($status < 400) $status = 500;
    }

    $contentType = __localdev_guess_content_type($body);
    $location = '';
    $scriptCookie = '';
    if (function_exists('headers_list')) {
        foreach (headers_list() as $headerLine) {
            if (stripos($headerLine, 'Content-Type:') === 0) {
                $candidate = trim(substr($headerLine, strlen('Content-Type:')));
                if ($candidate !== '') $contentType = $candidate;
            } elseif (stripos($headerLine, 'Location:') === 0) {
                $location = trim(substr($headerLine, strlen('Location:')));
                if ($status < 300 || $status >= 400) $status = 302;
            } elseif (stripos($headerLine, 'Set-Cookie:') === 0) {
                $scriptCookie = trim(substr($headerLine, strlen('Set-Cookie:')));
            }
        }
    }

    $sessionCookie = '';
    if (function_exists('session_status') && function_exists('session_name') && function_exists('session_id')) {
        $isActive = session_status() === PHP_SESSION_ACTIVE;
        $wasStarted = !empty($GLOBALS['__LOCALDEV_SESSION_STARTED']) || $isActive;
        if ($wasStarted) {
            $sessionName = (string) session_name();
            $sessionId = (string) session_id();
            if ($sessionName !== '' && $sessionId !== '') {
                $incomingId = (string) ($GLOBALS['__LOCALDEV_SESSION_INCOMING_ID'] ?? '');
                $cookieDirty = !empty($GLOBALS['__LOCALDEV_SESSION_COOKIE_DIRTY']);
                $alreadyHasCurrentSession = $incomingId !== '' && hash_equals($incomingId, $sessionId);
                // Re-send the cookie when it is new OR after session_regenerate_id(). The old
                // v0.9.3 logic only checked whether any session cookie arrived, which meant a
                // regenerated ID was never returned to the browser and the next request lost
                // the authenticated/CSRF session.
                if ($cookieDirty || !$alreadyHasCurrentSession) {
                    $sessionCookie = $sessionName . '=' . rawurlencode($sessionId) . '; Path=/; HttpOnly; SameSite=Lax';
                }
            }
        }
        if ($isActive && function_exists('session_write_close')) @session_write_close();
    }

    echo "===LOCALDEV_RESPONSE===\n";
    echo "LOCALDEV_STATUS:" . $status . "\n";
    echo "LOCALDEV_CONTENT_TYPE:" . $contentType . "\n";
    if ($location !== '') echo "LOCALDEV_LOCATION:" . $location . "\n";
    if ($scriptCookie !== '') echo "LOCALDEV_SET_COOKIE:" . $scriptCookie . "\n";
    elseif ($sessionCookie !== '') echo "LOCALDEV_SET_COOKIE:" . $sessionCookie . "\n";
    if (function_exists('session_name')) {
        $finalSessionName = (string) session_name();
        if ($finalSessionName !== '') echo "LOCALDEV_SESSION_NAME:" . $finalSessionName . "\n";
    }
    if (function_exists('session_id')) {
        $finalSessionId = (string) session_id();
        if ($finalSessionId !== '') echo "LOCALDEV_SESSION_ID:" . $finalSessionId . "\n";
    }
    echo "\n";
    echo $body;
}

register_shutdown_function('__localdev_finish');

try {
    chdir(dirname($__ldScript));
    include $__ldScript;
} catch (Throwable $e) {
    http_response_code(500);
    echo '<pre style="white-space:pre-wrap">LocalDev PHP error: ' . htmlspecialchars($e->getMessage(), ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8')
        . "\n" . htmlspecialchars($e->getFile(), ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') . ':' . (int) $e->getLine() . '</pre>';
}

__localdev_finish();
