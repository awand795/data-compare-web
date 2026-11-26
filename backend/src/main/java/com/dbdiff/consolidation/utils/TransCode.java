package com.dbdiff.consolidation.utils;

import java.util.Base64;

public class TransCode {
    public static String encodeBasicAuth(String authUser, String authPassword) {
        byte[] encode = Base64.getEncoder().encode((authUser + ":" + authPassword).getBytes());
        return "Basic " + new String(encode);
    }

    public static String decodeBasicAuth(String text) {
        String auth = "";
        if (!text.isEmpty() && text.startsWith("Basic")) {
            text = text.substring("Basic".length()).trim();
            auth = new String(Base64.getDecoder().decode(text));
        }
        return auth;
    }

    public static Boolean validateAuth(String authorization, String authUser, String authPassword) {
        if (authorization != null && !authorization.isEmpty()) {
            authorization = decodeBasicAuth(authorization);
            return authorization.equalsIgnoreCase(authUser + ":" + authPassword);
        }
        return false;
    }
}
