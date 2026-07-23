package com.chatchat.license;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MachineIdentity {

    private static final List<String> VIRTUAL_INTERFACE_MARKERS = List.of(
        "virtual", "vmnet", "vmware network adapter", "vbox", "virtualbox",
        "hyper-v", "vethernet", "docker", "container", "veth", "virbr",
        "bridge", "loopback", "tunnel", "teredo", "miniport", "tap-",
        "tap_", "tun", "vpn", "openvpn", "wireguard", "tailscale", "wsl",
        "bluetooth", "personal area network"
    );

    private MachineIdentity() {
    }

    public static String resolve(Path serverIdFile) {
        try {
            String serverId = calculate();
            if (serverIdFile != null) {
                Path parent = serverIdFile.toAbsolutePath().normalize().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(serverIdFile, serverId + System.lineSeparator(), StandardCharsets.UTF_8);
            }
            return serverId;
        } catch (Exception ex) {
            throw new LicenseException("无法生成服务器机器码: " + ex.getMessage(), ex);
        }
    }

    public static List<String> macAddresses() {
        try {
            List<NetworkInterface> candidates = new ArrayList<>();
            for (NetworkInterface item : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (isPhysicalCandidate(item)) candidates.add(item);
            }
            List<NetworkInterface> active = candidates.stream()
                .filter(item -> !isDown(item))
                .toList();
            List<NetworkInterface> available = active.isEmpty() ? candidates : active;
            List<NetworkInterface> globallyAssigned = available.stream()
                .filter(MachineIdentity::hasGloballyAssignedMac)
                .toList();
            List<NetworkInterface> selected = globallyAssigned.isEmpty() ? available : globallyAssigned;
            return selected.stream()
                .sorted(Comparator.comparing(MachineIdentity::isDown)
                    .thenComparingInt(NetworkInterface::getIndex))
                .map(MachineIdentity::readMac)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        } catch (Exception ex) {
            throw new LicenseException("无法读取服务器 MAC 地址: " + ex.getMessage(), ex);
        }
    }

    public static String normalizeMac(String value) {
        if (value == null || value.isBlank()) return null;
        String hex = value.trim().replaceFirst("(?i)^MAC[-:]?", "")
            .replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (hex.length() != 12) return null;
        return "MAC-" + hex;
    }

    public static boolean matchesMac(String value) {
        String normalized = normalizeMac(value);
        return normalized != null && macAddresses().contains(normalized);
    }

    private static String calculate() throws Exception {
        List<String> signals = new ArrayList<>();
        signals.add(System.getProperty("os.name", ""));
        signals.add(System.getProperty("os.arch", ""));
        signals.add(InetAddress.getLocalHost().getHostName());
        signals.addAll(macAddresses());
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(String.join("|", signals).getBytes(StandardCharsets.UTF_8));
        return "SERVER-" + java.util.HexFormat.of().formatHex(hash, 0, 12).toUpperCase();
    }

    private static boolean isPhysicalCandidate(NetworkInterface item) {
        try {
            byte[] hardwareAddress = item.getHardwareAddress();
            if (item.isLoopback() || item.isVirtual() || item.isPointToPoint()
                || hardwareAddress == null || hardwareAddress.length != 6
                || isEmptyOrMulticast(hardwareAddress)) {
                return false;
            }
            String identity = (item.getName() + " " + item.getDisplayName()).toLowerCase(Locale.ROOT);
            return VIRTUAL_INTERFACE_MARKERS.stream().noneMatch(identity::contains);
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean hasGloballyAssignedMac(NetworkInterface item) {
        try {
            byte[] mac = item.getHardwareAddress();
            return mac != null && mac.length == 6 && (mac[0] & 0x02) == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean isDown(NetworkInterface item) {
        try {
            return !item.isUp();
        } catch (Exception ex) {
            return true;
        }
    }

    private static String readMac(NetworkInterface item) {
        try {
            return normalizeMac(java.util.HexFormat.of().formatHex(item.getHardwareAddress()));
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isEmptyOrMulticast(byte[] mac) {
        boolean allZero = true;
        for (byte value : mac) {
            if (value != 0) {
                allZero = false;
                break;
            }
        }
        return allZero || (mac[0] & 0x01) != 0;
    }
}
