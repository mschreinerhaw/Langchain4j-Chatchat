package com.chatchat.tools.playwright;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared Playwright setup used by tools that need browser rendering.
 */
@Slf4j
public class PlaywrightBrowserSupport {

    private final String logPrefix;
    private volatile boolean browsersPathInfoLogged;
    private volatile boolean browsersPathWarningLogged;
    private volatile boolean skipDownloadInfoLogged;

    public PlaywrightBrowserSupport(String logPrefix) {
        this.logPrefix = logPrefix == null || logPrefix.isBlank() ? "Playwright" : logPrefix.trim();
    }

    public Playwright createPlaywright(BrowserConfig browserConfig) {
        return Playwright.create(new Playwright.CreateOptions().setEnv(playwrightEnvironment(browserConfig)));
    }

    public BrowserType.LaunchOptions headlessLaunchOptions(int timeoutMs, ProxyConfig proxyConfig) {
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
            .setHeadless(true)
            .setTimeout(Math.max(0, timeoutMs));
        com.microsoft.playwright.options.Proxy proxy = playwrightProxy(proxyConfig);
        if (proxy != null) {
            launchOptions.setProxy(proxy);
        }
        return launchOptions;
    }

    public com.microsoft.playwright.options.Proxy playwrightProxy(ProxyConfig proxyConfig) {
        if (proxyConfig == null
            || !proxyConfig.enabled()
            || proxyConfig.host() == null
            || proxyConfig.host().isBlank()
            || proxyConfig.port() <= 0) {
            return null;
        }
        String type = firstNonBlank(proxyConfig.type(), "HTTP").toLowerCase(Locale.ROOT);
        String scheme = type.contains("socks") ? "socks5" : "http";
        com.microsoft.playwright.options.Proxy proxy =
            new com.microsoft.playwright.options.Proxy(scheme + "://" + proxyConfig.host().trim() + ":" + proxyConfig.port());
        if (proxyConfig.username() != null && !proxyConfig.username().isBlank()) {
            proxy.setUsername(proxyConfig.username());
            proxy.setPassword(firstNonBlank(proxyConfig.password(), ""));
        }
        return proxy;
    }

    public void waitForNetworkIdle(Page page, int timeoutMs, String debugMessage) {
        if (page == null || timeoutMs <= 0) {
            return;
        }
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(Math.min(3000, Math.max(1000, timeoutMs / 3))));
        } catch (RuntimeException ex) {
            log.debug("{}: {}", firstNonBlank(debugMessage, "Playwright page did not reach networkidle"), ex.getMessage());
        }
    }

    public Map<String, String> playwrightEnvironment(BrowserConfig browserConfig) {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        String configuredBrowsersPath = browserConfig == null ? null : browserConfig.browsersPath();
        String browsersPath = firstNonBlank(configuredBrowsersPath, env.get("PLAYWRIGHT_BROWSERS_PATH"));
        if (browsersPath != null && !browsersPath.isBlank()) {
            String normalizedPath = normalizePlaywrightBrowsersPath(browsersPath);
            env.put("PLAYWRIGHT_BROWSERS_PATH", normalizedPath);
            logPlaywrightBrowsersPath(normalizedPath);
            if (containsPlaywrightChromiumInstall(Path.of(normalizedPath))) {
                ensureChromiumExecutables(Path.of(normalizedPath));
                env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
                logPlaywrightSkipDownload(normalizedPath);
            }
        }
        if (browserConfig != null && browserConfig.skipBrowserDownload()) {
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        }
        return env;
    }

    public String normalizePlaywrightBrowsersPath(String browsersPath) {
        String value = browsersPath == null ? "" : browsersPath.trim();
        if (value.isBlank() || "0".equals(value)) {
            return value;
        }
        Path path = resolvePlaywrightBrowsersPath(value);
        if (containsPlaywrightChromiumInstall(path)) {
            return path.toString();
        }
        String platformDirectory = playwrightPlatformDirectoryName();
        if (platformDirectory != null) {
            Path platformPath = path.resolve(platformDirectory);
            if (Files.isDirectory(platformPath)) {
                return platformPath.toString();
            }
        }
        return path.toString();
    }

    public Path resolvePlaywrightBrowsersPath(String browsersPath) {
        Path configuredPath = Path.of(browsersPath);
        if (configuredPath.isAbsolute()) {
            return configuredPath.normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path directPath = cwd.resolve(configuredPath).normalize();
        if (Files.exists(directPath)) {
            return directPath;
        }
        for (Path parent = cwd.getParent(); parent != null; parent = parent.getParent()) {
            Path candidate = parent.resolve(configuredPath).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return directPath;
    }

    public boolean containsPlaywrightChromiumInstall(Path path) {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (java.util.stream.Stream<Path> children = Files.list(path)) {
            return children
                .map(child -> child.getFileName() == null ? "" : child.getFileName().toString())
                .anyMatch(name -> name.startsWith("chromium-")
                    || name.startsWith("chromium_headless_shell-"));
        } catch (IOException ex) {
            return false;
        }
    }

    void ensureChromiumExecutables(Path browsersPath) {
        if (browsersPath == null || !Files.isDirectory(browsersPath)
            || !"linux".equals(playwrightPlatformDirectoryName())) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(browsersPath, 5)) {
            paths.filter(Files::isRegularFile)
                .filter(this::isChromiumLauncher)
                .forEach(this::ensureExecutable);
        } catch (IOException ex) {
            log.warn("{} could not inspect Playwright Chromium permissions under {}: {}",
                logPrefix, browsersPath, ex.getMessage());
        }
    }

    private boolean isChromiumLauncher(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return "headless_shell".equals(name) || "chrome".equals(name) || "chrome-wrapper".equals(name);
    }

    private void ensureExecutable(Path launcher) {
        if (Files.isExecutable(launcher)) {
            return;
        }
        try {
            EnumSet<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(launcher));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(launcher, permissions);
            log.info("{} repaired executable permission for Playwright Chromium launcher: {}", logPrefix, launcher);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            log.warn("{} Playwright Chromium launcher is not executable and permission repair failed for {}: {}",
                logPrefix, launcher, ex.getMessage());
        }
    }

    public String playwrightPlatformDirectoryName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "mac";
        }
        return null;
    }

    private void logPlaywrightBrowsersPath(String browsersPath) {
        if (!browsersPathInfoLogged) {
            synchronized (this) {
                if (!browsersPathInfoLogged) {
                    log.info("{} Playwright browser cache path: {}", logPrefix, browsersPath);
                    browsersPathInfoLogged = true;
                }
            }
        }
        if (!"0".equals(browsersPath) && !Files.isDirectory(Path.of(browsersPath)) && !browsersPathWarningLogged) {
            synchronized (this) {
                if (!browsersPathWarningLogged) {
                    log.warn("Configured {} Playwright browser cache path does not exist yet: {}. "
                        + "Pre-download Chromium into this directory before running offline.", logPrefix, browsersPath);
                    browsersPathWarningLogged = true;
                }
            }
        }
    }

    private void logPlaywrightSkipDownload(String browsersPath) {
        if (!skipDownloadInfoLogged) {
            synchronized (this) {
                if (!skipDownloadInfoLogged) {
                    log.info("{} Playwright browser download skipped because cached Chromium was found: {}",
                        logPrefix,
                        browsersPath);
                    skipDownloadInfoLogged = true;
                }
            }
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    public record BrowserConfig(String browsersPath, boolean skipBrowserDownload) {
    }

    public record ProxyConfig(boolean enabled,
                              String type,
                              String host,
                              int port,
                              String username,
                              String password) {
    }
}
