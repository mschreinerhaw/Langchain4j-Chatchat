package com.chatchat.common.security;

public final class InternalSecretTool {

    private InternalSecretTool() {
    }

    public static void main(String[] args) {
        if (args == null || args.length < 2 || !"encrypt".equalsIgnoreCase(args[0])) {
            System.err.println("Usage: java ... com.chatchat.common.security.InternalSecretTool encrypt <secret> [cryptoKey]");
            System.err.println("Or set CHATCHAT_INTERNAL_CRYPTO_KEY when cryptoKey is omitted.");
            System.exit(2);
            return;
        }
        String key = args.length >= 3 ? args[2] : System.getenv("CHATCHAT_INTERNAL_CRYPTO_KEY");
        System.out.println(InternalSecretCipher.encrypt(args[1], key));
    }
}
