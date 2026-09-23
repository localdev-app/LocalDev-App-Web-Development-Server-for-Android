<?php
declare(strict_types=1);

/**
 * LocalDev Web Extractor
 * Runs inside the bundled PHP CLI runtime and intentionally uses cURL.
 * Output: one JSON object on stdout.
 */

ini_set('display_errors', '0');
ini_set('html_errors', '0');
error_reporting(E_ALL);

function out(array $data, int $code = 0): never {
    echo json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit($code);
}

function fail(string $message, array $warnings = []): never {
    out(['ok' => false, 'error' => $message, 'warnings' => $warnings], 2);
}

if (!extension_loaded('curl')) {
    fail('Extension PHP cURL tidak tersedia pada runtime LocalDev ini.');
}

$url = trim((string)($argv[1] ?? ''));
$outDir = (string)($argv[2] ?? '');
$downloadAssets = ((string)($argv[3] ?? '0')) === '1';

if ($url === '' || $outDir === '') {
    fail('Argumen Web Extractor tidak lengkap.');
}

function isPublicHttpUrl(string $url): bool {
    $p = @parse_url($url);
    if (!is_array($p)) return false;
    $scheme = strtolower((string)($p['scheme'] ?? ''));
    $host = strtolower(rtrim((string)($p['host'] ?? ''), '.'));
    if (!in_array($scheme, ['http', 'https'], true) || $host === '') return false;
    if (isset($p['user']) || isset($p['pass'])) return false;

    if ($host === 'localhost' || str_ends_with($host, '.localhost') || str_ends_with($host, '.local') || str_ends_with($host, '.internal')) {
        return false;
    }

    if (filter_var($host, FILTER_VALIDATE_IP)) {
        return (bool)filter_var($host, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE);
    }

    $resolved = [];
    $ipv4 = @gethostbyname($host);
    if ($ipv4 !== $host && filter_var($ipv4, FILTER_VALIDATE_IP)) $resolved[] = $ipv4;
    if (function_exists('dns_get_record') && defined('DNS_A') && defined('DNS_AAAA')) {
        $records = @dns_get_record($host, DNS_A | DNS_AAAA);
        if (is_array($records)) {
            foreach ($records as $record) {
                $ip = (string)($record['ip'] ?? $record['ipv6'] ?? '');
                if ($ip !== '' && filter_var($ip, FILTER_VALIDATE_IP)) $resolved[] = $ip;
            }
        }
    }
    foreach (array_unique($resolved) as $ip) {
        if (!filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE)) {
            return false;
        }
    }
    return true;
}

if (!preg_match('~^https?://~i', $url)) {
    $url = 'https://' . $url;
}
if (!isPublicHttpUrl($url)) {
    fail('URL harus memakai HTTP/HTTPS publik. localhost, IP private, file://, dan skema non-web diblokir.');
}

function removeDotSegments(string $path): string {
    $leading = str_starts_with($path, '/');
    $trailing = str_ends_with($path, '/');
    $stack = [];
    foreach (explode('/', $path) as $part) {
        if ($part === '' || $part === '.') continue;
        if ($part === '..') {
            array_pop($stack);
        } else {
            $stack[] = $part;
        }
    }
    $normalized = ($leading ? '/' : '') . implode('/', $stack);
    if ($trailing && $normalized !== '/' && $normalized !== '') $normalized .= '/';
    return $normalized === '' && $leading ? '/' : $normalized;
}

function resolveUrl(string $base, string $ref): string {
    $ref = trim(html_entity_decode($ref, ENT_QUOTES | ENT_HTML5, 'UTF-8'));
    if ($ref === '') return $base;
    if (preg_match('~^(?:data:|javascript:|mailto:|tel:|blob:|about:)~i', $ref) || str_starts_with($ref, '#')) return $ref;
    if (preg_match('~^https?://~i', $ref)) return $ref;

    $b = @parse_url($base);
    if (!is_array($b) || empty($b['scheme']) || empty($b['host'])) return $ref;
    $scheme = (string)$b['scheme'];
    $host = (string)$b['host'];
    $port = isset($b['port']) ? ':' . (int)$b['port'] : '';
    $origin = $scheme . '://' . $host . $port;

    if (str_starts_with($ref, '//')) return $scheme . ':' . $ref;
    if (str_starts_with($ref, '?')) {
        $path = (string)($b['path'] ?? '/');
        return $origin . ($path === '' ? '/' : $path) . $ref;
    }

    $fragment = '';
    $hash = strpos($ref, '#');
    if ($hash !== false) {
        $fragment = substr($ref, $hash);
        $ref = substr($ref, 0, $hash);
    }
    $query = '';
    $q = strpos($ref, '?');
    if ($q !== false) {
        $query = substr($ref, $q);
        $ref = substr($ref, 0, $q);
    }

    if (str_starts_with($ref, '/')) {
        $path = removeDotSegments($ref);
    } else {
        $basePath = (string)($b['path'] ?? '/');
        $dir = preg_replace('~/[^/]*$~', '/', $basePath) ?: '/';
        $path = removeDotSegments($dir . $ref);
        if (!str_starts_with($path, '/')) $path = '/' . $path;
    }
    return $origin . $path . $query . $fragment;
}

function fetchUrl(string $url, int $maxBytes, ?string $referer = null): array {
    $current = $url;
    for ($redirect = 0; $redirect <= 8; $redirect++) {
        if (!isPublicHttpUrl($current)) {
            throw new RuntimeException('Host non-publik diblokir: ' . $current);
        }

        $ch = curl_init($current);
        if ($ch === false) throw new RuntimeException('cURL init gagal.');
        $body = '';
        $tooLarge = false;
        $location = '';

        $opts = [
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_TIMEOUT => 30,
            CURLOPT_RETURNTRANSFER => false,
            CURLOPT_HEADER => false,
            CURLOPT_ENCODING => '',
            CURLOPT_USERAGENT => 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36 LocalDev/1.1',
            CURLOPT_HTTPHEADER => ['Accept-Language: id-ID,id;q=0.9,en;q=0.8'],
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_HEADERFUNCTION => static function ($ch, string $line) use (&$location): int {
                if (stripos($line, 'Location:') === 0) {
                    $location = trim(substr($line, 9));
                }
                return strlen($line);
            },
            CURLOPT_WRITEFUNCTION => static function ($ch, string $chunk) use (&$body, &$tooLarge, $maxBytes): int {
                if (strlen($body) + strlen($chunk) > $maxBytes) {
                    $tooLarge = true;
                    return 0;
                }
                $body .= $chunk;
                return strlen($chunk);
            },
        ];
        if ($referer) $opts[CURLOPT_REFERER] = $referer;
        if (defined('CURLOPT_PROTOCOLS') && defined('CURLPROTO_HTTP') && defined('CURLPROTO_HTTPS')) {
            $opts[CURLOPT_PROTOCOLS] = CURLPROTO_HTTP | CURLPROTO_HTTPS;
        }
        curl_setopt_array($ch, $opts);
        $ok = curl_exec($ch);
        $errno = curl_errno($ch);
        $error = curl_error($ch);
        $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        $effective = (string)curl_getinfo($ch, CURLINFO_EFFECTIVE_URL);
        $contentType = (string)curl_getinfo($ch, CURLINFO_CONTENT_TYPE);
        curl_close($ch);

        if ($tooLarge) throw new RuntimeException('Resource terlalu besar: ' . $current);
        if ($ok === false || $errno !== 0) throw new RuntimeException('cURL gagal: ' . ($error ?: ('errno ' . $errno)));

        $effective = $effective !== '' ? $effective : $current;
        if ($status >= 300 && $status < 400 && $location !== '') {
            $next = resolveUrl($effective, $location);
            if (!isPublicHttpUrl($next)) throw new RuntimeException('Redirect ke host non-publik diblokir.');
            $referer = $effective;
            $current = $next;
            continue;
        }

        if ($status < 200 || $status >= 400) throw new RuntimeException('HTTP ' . $status . ': ' . $effective);
        return [
            'body' => $body,
            'status' => $status,
            'effective' => $effective,
            'content_type' => $contentType,
        ];
    }
    throw new RuntimeException('Terlalu banyak redirect.');
}

function attrValue(string $tag, string $name): ?string {
    $name = preg_quote($name, '~');
    if (preg_match('~\\b' . $name . '\\s*=\\s*(["\'])(.*?)\\1~is', $tag, $m)) return $m[2];
    if (preg_match('~\\b' . $name . '\\s*=\\s*([^\\s>]+)~is', $tag, $m)) return trim($m[1], "\"'");
    return null;
}

function replaceAttr(string $tag, string $name, string $value): string {
    $escaped = htmlspecialchars($value, ENT_QUOTES | ENT_HTML5, 'UTF-8');
    $pattern = '~(\\b' . preg_quote($name, '~') . '\\s*=\\s*)(["\'])(.*?)\\2~is';
    if (preg_match($pattern, $tag)) {
        return preg_replace_callback($pattern, static fn($m) => $m[1] . $m[2] . $escaped . $m[2], $tag, 1) ?? $tag;
    }
    $pattern2 = '~(\\b' . preg_quote($name, '~') . '\\s*=\\s*)([^\\s>]+)~is';
    if (preg_match($pattern2, $tag)) {
        return preg_replace_callback($pattern2, static fn($m) => $m[1] . '"' . $escaped . '"', $tag, 1) ?? $tag;
    }
    return $tag;
}

function guessExtension(string $url, string $contentType): string {
    $path = (string)(parse_url($url, PHP_URL_PATH) ?? '');
    $ext = strtolower(pathinfo($path, PATHINFO_EXTENSION));
    $safe = ['png','jpg','jpeg','webp','gif','svg','ico','avif','bmp','woff','woff2','ttf','otf','eot','mp4','webm','mp3','ogg','wav','pdf'];
    if (in_array($ext, $safe, true)) return '.' . $ext;
    $type = strtolower(trim(explode(';', $contentType)[0] ?? ''));
    return match ($type) {
        'image/png' => '.png', 'image/jpeg' => '.jpg', 'image/webp' => '.webp', 'image/gif' => '.gif',
        'image/svg+xml' => '.svg', 'image/x-icon', 'image/vnd.microsoft.icon' => '.ico', 'image/avif' => '.avif',
        'font/woff' => '.woff', 'font/woff2' => '.woff2', 'font/ttf', 'application/x-font-ttf' => '.ttf',
        'font/otf', 'application/x-font-opentype' => '.otf', 'application/vnd.ms-fontobject' => '.eot',
        'video/mp4' => '.mp4', 'video/webm' => '.webm', 'audio/mpeg' => '.mp3', 'audio/ogg' => '.ogg',
        'application/pdf' => '.pdf',
        default => '.bin',
    };
}

@mkdir($outDir, 0700, true);
$cssDir = $outDir . DIRECTORY_SEPARATOR . 'css';
$assetsDir = $outDir . DIRECTORY_SEPARATOR . 'assets';
@mkdir($cssDir, 0700, true);
if ($downloadAssets) @mkdir($assetsDir, 0700, true);

$warnings = [];
$assetMap = [];
$assetCount = 0;
$totalAssetBytes = 0;
$maxAssets = 100;
$maxAssetTotal = 40 * 1024 * 1024;

function saveAsset(string $absolute, string $pageUrl, string $outDir, string $assetsDir, array &$assetMap, int &$assetCount, int &$totalAssetBytes, int $maxAssets, int $maxAssetTotal, array &$warnings, string $prefix): string {
    if (isset($assetMap[$absolute])) return $prefix . $assetMap[$absolute];
    if ($assetCount >= $maxAssets || $totalAssetBytes >= $maxAssetTotal) {
        $warnings[] = 'Batas asset tercapai; sebagian asset tetap memakai URL online.';
        return $absolute;
    }
    try {
        $res = fetchUrl($absolute, min(5 * 1024 * 1024, max(1, $maxAssetTotal - $totalAssetBytes)), $pageUrl);
        $bytes = strlen($res['body']);
        if ($bytes <= 0) return $absolute;
        $ext = guessExtension($absolute, (string)$res['content_type']);
        $assetCount++;
        $name = 'asset-' . $assetCount . $ext;
        file_put_contents($assetsDir . DIRECTORY_SEPARATOR . $name, $res['body']);
        $assetMap[$absolute] = $name;
        $totalAssetBytes += $bytes;
        return $prefix . $name;
    } catch (Throwable $e) {
        $warnings[] = 'Asset gagal: ' . $absolute . ' • ' . $e->getMessage();
        return $absolute;
    }
}

function processCss(string $css, string $cssBaseUrl, string $pageUrl, bool $downloadAssets, string $outDir, string $assetsDir, array &$assetMap, int &$assetCount, int &$totalAssetBytes, int $maxAssets, int $maxAssetTotal, array &$warnings): string {
    // Keep nested imports online, but make them absolute so the local stylesheet resolves correctly.
    $css = preg_replace_callback('~@import\\s+(?:url\\(\\s*)?(["\']?)([^"\')\\s;]+)\\1\\s*\\)?~i', static function ($m) use ($cssBaseUrl) {
        $abs = resolveUrl($cssBaseUrl, $m[2]);
        return '@import url("' . str_replace('"', '%22', $abs) . '")';
    }, $css) ?? $css;

    $css = preg_replace_callback('~url\\(\\s*(["\']?)(.*?)\\1\\s*\\)~is', static function ($m) use ($cssBaseUrl, $pageUrl, $downloadAssets, $outDir, $assetsDir, &$assetMap, &$assetCount, &$totalAssetBytes, $maxAssets, $maxAssetTotal, &$warnings) {
        $raw = trim($m[2]);
        if ($raw === '' || str_starts_with($raw, '#') || preg_match('~^(?:data:|blob:)~i', $raw)) return $m[0];
        $abs = resolveUrl($cssBaseUrl, $raw);
        if (!$downloadAssets || !preg_match('~^https?://~i', $abs)) return 'url("' . str_replace('"', '%22', $abs) . '")';
        $local = saveAsset($abs, $pageUrl, $outDir, $assetsDir, $assetMap, $assetCount, $totalAssetBytes, $maxAssets, $maxAssetTotal, $warnings, '../assets/');
        return 'url("' . str_replace('"', '%22', $local) . '")';
    }, $css) ?? $css;
    return $css;
}

try {
    $page = fetchUrl($url, 6 * 1024 * 1024);
    $html = (string)$page['body'];
    $pageUrl = (string)$page['effective'];
    $contentType = strtolower((string)$page['content_type']);
    if ($contentType !== '' && !str_contains($contentType, 'text/html') && !str_contains($contentType, 'application/xhtml')) {
        $warnings[] = 'Content-Type bukan HTML: ' . $contentType;
    }

    $baseUrl = $pageUrl;
    if (preg_match('~<base\\b[^>]*\\bhref\\s*=\\s*(["\'])(.*?)\\1[^>]*>~is', $html, $bm)) {
        $candidate = resolveUrl($pageUrl, $bm[2]);
        if (preg_match('~^https?://~i', $candidate)) $baseUrl = $candidate;
    }

    $cssFiles = [];
    $cssIndex = 0;

    // Download external stylesheets and replace their href with local css/style-N.css.
    $html = preg_replace_callback('~<link\\b[^>]*>~is', static function ($m) use (&$cssIndex, &$cssFiles, $cssDir, $baseUrl, $pageUrl, $downloadAssets, $outDir, $assetsDir, &$assetMap, &$assetCount, &$totalAssetBytes, $maxAssets, $maxAssetTotal, &$warnings) {
        $tag = $m[0];
        $rel = strtolower((string)(attrValue($tag, 'rel') ?? ''));
        $href = attrValue($tag, 'href');
        if ($href === null || !preg_match('~(?:^|\\s)stylesheet(?:\\s|$)~i', $rel)) return $tag;
        if ($cssIndex >= 30) {
            $warnings[] = 'Lebih dari 30 stylesheet; sisanya dibiarkan online.';
            return replaceAttr($tag, 'href', resolveUrl($baseUrl, $href));
        }
        $absolute = resolveUrl($baseUrl, $href);
        if (!preg_match('~^https?://~i', $absolute)) return $tag;
        try {
            $res = fetchUrl($absolute, 3 * 1024 * 1024, $pageUrl);
            $cssIndex++;
            $name = 'style-' . $cssIndex . '.css';
            $css = processCss((string)$res['body'], (string)$res['effective'], $pageUrl, $downloadAssets, $outDir, $assetsDir, $assetMap, $assetCount, $totalAssetBytes, $maxAssets, $maxAssetTotal, $warnings);
            file_put_contents($cssDir . DIRECTORY_SEPARATOR . $name, $css);
            $cssFiles[] = 'css/' . $name;
            return replaceAttr($tag, 'href', 'css/' . $name);
        } catch (Throwable $e) {
            $warnings[] = 'CSS gagal: ' . $absolute . ' • ' . $e->getMessage();
            return replaceAttr($tag, 'href', $absolute);
        }
    }, $html) ?? $html;

    // Extract inline <style> blocks to css/inline.css.
    $inlineBlocks = [];
    $html = preg_replace_callback('~<style\b([^>]*)>(.*?)</style>~is', static function ($m) use (&$inlineBlocks) {
        $opening = '<style ' . $m[1] . '>';
        $media = trim((string)(attrValue($opening, 'media') ?? ''));
        $content = $m[2];
        if ($media !== '') {
            $content = "@media " . $media . " {\n" . $content . "\n}";
        }
        $inlineBlocks[] = $content;
        return '';
    }, $html) ?? $html;
    if ($inlineBlocks) {
        $inlineCss = implode("\n\n/* ---- LocalDev inline block ---- */\n\n", $inlineBlocks);
        $inlineCss = processCss($inlineCss, $baseUrl, $pageUrl, $downloadAssets, $outDir, $assetsDir, $assetMap, $assetCount, $totalAssetBytes, $maxAssets, $maxAssetTotal, $warnings);
        file_put_contents($cssDir . DIRECTORY_SEPARATOR . 'inline.css', $inlineCss);
        $cssFiles[] = 'css/inline.css';
        $link = '<link rel="stylesheet" href="css/inline.css" data-localdev-inline="1">';
        if (stripos($html, '</head>') !== false) {
            $html = preg_replace('~</head>~i', $link . "\n</head>", $html, 1) ?? ($link . $html);
        } else {
            $html = $link . "\n" . $html;
        }
    }

    // Download common HTML images/icons when requested.
    if ($downloadAssets) {
        $html = preg_replace_callback('~<(?:img|source)\\b[^>]*>~is', static function ($m) use ($baseUrl, $pageUrl, $outDir, $assetsDir, &$assetMap, &$assetCount, &$totalAssetBytes, $maxAssets, $maxAssetTotal, &$warnings) {
            $tag = $m[0];
            $src = attrValue($tag, 'src');
            if ($src !== null) {
                $abs = resolveUrl($baseUrl, $src);
                if (preg_match('~^https?://~i', $abs)) {
                    $local = saveAsset($abs, $pageUrl, $outDir, $assetsDir, $assetMap, $assetCount, $totalAssetBytes, $maxAssets, $maxAssetTotal, $warnings, 'assets/');
                    $tag = replaceAttr($tag, 'src', $local);
                }
            }
            $srcset = attrValue($tag, 'srcset');
            if ($srcset !== null) {
                $parts = [];
                foreach (explode(',', $srcset) as $candidate) {
                    $candidate = trim($candidate);
                    if ($candidate === '') continue;
                    $bits = preg_split('/\\s+/', $candidate, 2);
                    $abs = resolveUrl($baseUrl, (string)$bits[0]);
                    $u = preg_match('~^https?://~i', $abs)
                        ? saveAsset($abs, $pageUrl, $outDir, $assetsDir, $assetMap, $assetCount, $totalAssetBytes, $maxAssets, $maxAssetTotal, $warnings, 'assets/')
                        : $abs;
                    $parts[] = $u . (isset($bits[1]) ? ' ' . $bits[1] : '');
                }
                if ($parts) $tag = replaceAttr($tag, 'srcset', implode(', ', $parts));
            }
            return $tag;
        }, $html) ?? $html;

        $html = preg_replace_callback('~<link\\b[^>]*>~is', static function ($m) use ($baseUrl, $pageUrl, $outDir, $assetsDir, &$assetMap, &$assetCount, &$totalAssetBytes, $maxAssets, $maxAssetTotal, &$warnings) {
            $tag = $m[0];
            $rel = strtolower((string)(attrValue($tag, 'rel') ?? ''));
            if (!preg_match('~(?:icon|apple-touch-icon|mask-icon)~i', $rel)) return $tag;
            $href = attrValue($tag, 'href');
            if ($href === null || str_starts_with($href, 'assets/')) return $tag;
            $abs = resolveUrl($baseUrl, $href);
            if (!preg_match('~^https?://~i', $abs)) return $tag;
            $local = saveAsset($abs, $pageUrl, $outDir, $assetsDir, $assetMap, $assetCount, $totalAssetBytes, $maxAssets, $maxAssetTotal, $warnings, 'assets/');
            return replaceAttr($tag, 'href', $local);
        }, $html) ?? $html;
    }

    // Inline style attributes: rewrite/download url(...) references.
    $html = preg_replace_callback('~\\bstyle\\s*=\\s*(["\'])(.*?)\\1~is', static function ($m) use ($baseUrl, $pageUrl, $downloadAssets, $outDir, $assetsDir, &$assetMap, &$assetCount, &$totalAssetBytes, $maxAssets, $maxAssetTotal, &$warnings) {
        $css = processCss($m[2], $baseUrl, $pageUrl, $downloadAssets, $outDir, $assetsDir, $assetMap, $assetCount, $totalAssetBytes, $maxAssets, $maxAssetTotal, $warnings);
        return 'style=' . $m[1] . $css . $m[1];
    }, $html) ?? $html;

    // Normalize any remaining srcset values. When asset download is disabled this keeps image candidates working in local preview.
    $html = preg_replace_callback('~\\bsrcset\\s*=\\s*(["\'])(.*?)\\1~is', static function ($m) use ($baseUrl) {
        $parts = [];
        foreach (explode(',', $m[2]) as $candidate) {
            $candidate = trim($candidate);
            if ($candidate === '') continue;
            $bits = preg_split('/\\s+/', $candidate, 2);
            $raw = (string)($bits[0] ?? '');
            $url = preg_match('~^(?:assets/|data:|blob:)~i', $raw) ? $raw : resolveUrl($baseUrl, $raw);
            $parts[] = $url . (isset($bits[1]) ? ' ' . $bits[1] : '');
        }
        return 'srcset=' . $m[1] . implode(', ', $parts) . $m[1];
    }, $html) ?? $html;

    // Make remaining relative src/href/poster/action URLs absolute so static preview still works.
    $html = preg_replace_callback('~\\b(src|href|poster|action)\\s*=\\s*(["\'])(.*?)\\2~is', static function ($m) use ($baseUrl) {
        $name = strtolower($m[1]);
        $value = trim($m[3]);
        if ($value === '' || str_starts_with($value, '#') || preg_match('~^(?:data:|javascript:|mailto:|tel:|blob:|about:)~i', $value)) return $m[0];
        if (preg_match('~^(?:css|assets)/~i', $value)) return $m[0];
        $abs = resolveUrl($baseUrl, $value);
        return $name . '=' . $m[2] . htmlspecialchars($abs, ENT_QUOTES | ENT_HTML5, 'UTF-8') . $m[2];
    }, $html) ?? $html;

    // Add extraction metadata without altering visual output.
    $meta = '<meta name="generator" content="LocalDev Web Extractor 1.1">';
    if (stripos($html, '<head') !== false) {
        $html = preg_replace('~(<head\\b[^>]*>)~i', '$1' . "\n" . $meta, $html, 1) ?? $html;
    }

    file_put_contents($outDir . DIRECTORY_SEPARATOR . 'index.html', $html);
    file_put_contents($outDir . DIRECTORY_SEPARATOR . '.localdev-web-extract.json', json_encode([
        'source_url' => $url,
        'final_url' => $pageUrl,
        'extracted_at' => gmdate('c'),
        'download_assets' => $downloadAssets,
        'css_files' => $cssFiles,
        'asset_count' => $assetCount,
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));

    $title = '';
    if (preg_match('~<title\\b[^>]*>(.*?)</title>~is', $html, $tm)) {
        $title = trim(html_entity_decode(strip_tags($tm[1]), ENT_QUOTES | ENT_HTML5, 'UTF-8'));
    }

    out([
        'ok' => true,
        'source_url' => $url,
        'final_url' => $pageUrl,
        'title' => $title,
        'html_file' => 'index.html',
        'css_files' => $cssFiles,
        'asset_count' => $assetCount,
        'asset_bytes' => $totalAssetBytes,
        'warnings' => array_values(array_unique($warnings)),
    ]);
} catch (Throwable $e) {
    fail($e->getMessage(), $warnings);
}
