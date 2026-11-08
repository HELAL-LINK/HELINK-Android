package com.alihelal.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * A web address, parsed and printed the way Go's net/url does it. java.net.URI rejects
 * many addresses people paste (with spaces, Arabic letters or "|"), and the link rules
 * were written and tested against Go's behaviour.
 */
public final class GoUrl {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public String scheme = "";
    public String opaque = "";
    public String userinfo; // null when absent
    public String host = "";
    public String path = "";
    public String rawPath = "";
    public boolean forceQuery;
    public String rawQuery = "";
    public String fragment = "";
    boolean omitHost;

    public static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String m) {
            super(m);
        }
    }

    private static final int ENC_PATH = 1, ENC_HOST = 3, ENC_QUERY_COMPONENT = 4, ENC_FRAGMENT = 5, ENC_USER = 2;

    public static GoUrl parse(String raw) throws ParseException {
        String u = raw, frag = "";
        int hash = raw.indexOf('#');
        if (hash >= 0) {
            u = raw.substring(0, hash);
            frag = raw.substring(hash + 1);
        }
        GoUrl url = parseNoFrag(u);
        if (frag.length() > 0) url.fragment = unescape(frag, ENC_FRAGMENT);
        return url;
    }

    private static GoUrl parseNoFrag(String raw) throws ParseException {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) throw new ParseException("invalid control character in URL");
        }
        GoUrl url = new GoUrl();
        if (raw.equals("*")) {
            url.path = "*";
            return url;
        }
        String rest = raw;
        // scheme
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                continue;
            }
            if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.') {
                if (i == 0) break;
                continue;
            }
            if (c == ':') {
                if (i == 0) throw new ParseException("missing protocol scheme");
                url.scheme = Str.lower(raw.substring(0, i));
                rest = raw.substring(i + 1);
            }
            break;
        }
        if (rest.endsWith("?") && count(rest, '?') == 1) {
            url.forceQuery = true;
            rest = rest.substring(0, rest.length() - 1);
        } else {
            int q = rest.indexOf('?');
            if (q >= 0) {
                url.rawQuery = rest.substring(q + 1);
                rest = rest.substring(0, q);
            }
        }
        if (!rest.startsWith("/")) {
            if (url.scheme.length() > 0) {
                url.opaque = rest;
                return url;
            }
            int colon = rest.indexOf(':');
            int slash = rest.indexOf('/');
            if (colon >= 0 && (slash < 0 || colon < slash)) {
                throw new ParseException("first path segment in URL cannot contain colon");
            }
        }
        if ((url.scheme.length() > 0 || !rest.startsWith("///")) && rest.startsWith("//")) {
            String authority = rest.substring(2);
            rest = "";
            int slash = authority.indexOf('/');
            if (slash >= 0) {
                rest = authority.substring(slash);
                authority = authority.substring(0, slash);
            }
            int at = authority.lastIndexOf('@');
            String hostPart = at < 0 ? authority : authority.substring(at + 1);
            url.host = parseHost(hostPart);
            if (at >= 0) url.userinfo = authority.substring(0, at);
        } else if (url.scheme.length() > 0 && rest.startsWith("/")) {
            url.omitHost = true;
        }
        url.setPath(rest);
        return url;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static String parseHost(String host) throws ParseException {
        if (host.startsWith("[")) {
            int i = host.lastIndexOf(']');
            if (i < 0) throw new ParseException("missing ']' in host");
            String colonPort = host.substring(i + 1);
            if (!validOptionalPort(colonPort)) throw new ParseException("invalid port");
            return unescape(host, ENC_HOST);
        }
        int i = host.lastIndexOf(':');
        if (i >= 0) {
            if (!validOptionalPort(host.substring(i))) throw new ParseException("invalid port");
        }
        return unescape(host, ENC_HOST);
    }

    private static boolean validOptionalPort(String port) {
        if (port.length() == 0) return true;
        if (port.charAt(0) != ':') return false;
        for (int i = 1; i < port.length(); i++) {
            char c = port.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private void setPath(String p) throws ParseException {
        String decoded = unescape(p, ENC_PATH);
        path = decoded;
        String esc = escape(decoded, ENC_PATH);
        rawPath = p.equals(esc) ? "" : p;
    }

    /** Hostname without the port. */
    public String hostname() {
        String h = host;
        if (h.startsWith("[")) {
            int i = h.indexOf(']');
            return i >= 0 ? h.substring(1, i) : h;
        }
        int colon = h.lastIndexOf(':');
        if (colon >= 0 && validOptionalPort(h.substring(colon))) return h.substring(0, colon);
        return h;
    }

    /** u.Query().Get(key) */
    public String queryGet(String key) {
        String q = rawQuery;
        while (q.length() > 0) {
            String pair;
            int amp = q.indexOf('&');
            if (amp >= 0) {
                pair = q.substring(0, amp);
                q = q.substring(amp + 1);
            } else {
                pair = q;
                q = "";
            }
            if (pair.indexOf(';') >= 0 || pair.length() == 0) continue;
            String k = pair, v = "";
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                k = pair.substring(0, eq);
                v = pair.substring(eq + 1);
            }
            try {
                k = unescape(k, ENC_QUERY_COMPONENT);
                v = unescape(v, ENC_QUERY_COMPONENT);
            } catch (ParseException e) {
                continue;
            }
            if (k.equals(key)) return v;
        }
        return "";
    }

    public String escapedPath() {
        if (rawPath.length() > 0 && validEncoded(rawPath, ENC_PATH)) {
            try {
                if (unescape(rawPath, ENC_PATH).equals(path)) return rawPath;
            } catch (ParseException ignored) {
                // fall through
            }
        }
        if (path.equals("*")) return "*";
        return escape(path, ENC_PATH);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        if (scheme.length() > 0) b.append(scheme).append(':');
        if (opaque.length() > 0) {
            b.append(opaque);
        } else {
            if (scheme.length() > 0 || host.length() > 0 || userinfo != null) {
                if (!(omitHost && host.length() == 0 && userinfo == null)) {
                    if (host.length() > 0 || path.length() > 0 || userinfo != null) b.append("//");
                    if (userinfo != null) b.append(userinfo).append('@');
                    if (host.length() > 0) b.append(escape(host, ENC_HOST));
                }
            }
            String p = escapedPath();
            if (p.length() > 0 && p.charAt(0) != '/' && host.length() > 0) b.append('/');
            if (b.length() == 0) {
                int slash = p.indexOf('/');
                String seg = slash >= 0 ? p.substring(0, slash) : p;
                if (seg.indexOf(':') >= 0) b.append("./");
            }
            b.append(p);
        }
        if (forceQuery || rawQuery.length() > 0) b.append('?').append(rawQuery);
        if (fragment.length() > 0) b.append('#').append(escape(fragment, ENC_FRAGMENT));
        return b.toString();
    }

    // ------------------------------------------------------------------ escaping (Go rules)

    private static boolean shouldEscape(int c, int mode) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return false;
        if (mode == ENC_HOST) {
            switch (c) {
                case '!': case '$': case '&': case '\'': case '(': case ')': case '*': case '+': case ',':
                case ';': case '=': case ':': case '[': case ']': case '<': case '>': case '"':
                    return false;
            }
        }
        switch (c) {
            case '-': case '_': case '.': case '~':
                return false;
            case '$': case '&': case '+': case ',': case '/': case ':': case ';': case '=': case '?': case '@':
                switch (mode) {
                    case ENC_PATH:
                        return c == '?';
                    case ENC_USER:
                        return c == '@' || c == '/' || c == '?' || c == ':';
                    case ENC_QUERY_COMPONENT:
                        return true;
                    case ENC_FRAGMENT:
                        return false;
                }
        }
        if (mode == ENC_FRAGMENT) {
            switch (c) {
                case '!': case '(': case ')': case '*':
                    return false;
            }
        }
        return true;
    }

    private static boolean validEncoded(String s, int mode) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '!': case '$': case '&': case '\'': case '(': case ')': case '*': case '+': case ',':
                case ';': case '=': case ':': case '@': case '[': case ']': case '%':
                    continue;
                default:
                    if (c >= 0x80 || shouldEscape(c, mode)) return false;
            }
        }
        return true;
    }

    private static int unhex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    static String unescape(String s, int mode) throws ParseException {
        boolean need = false;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%') {
                need = true;
                if (i + 2 >= s.length() || unhex(s.charAt(i + 1)) < 0 || unhex(s.charAt(i + 2)) < 0) {
                    throw new ParseException("invalid URL escape");
                }
                if (mode == ENC_HOST && unhex(s.charAt(i + 1)) < 8 && !s.startsWith("%25", i)) {
                    throw new ParseException("invalid URL escape in host");
                }
                i += 3;
            } else {
                if (c == '+' && mode == ENC_QUERY_COMPONENT) need = true;
                if (mode == ENC_HOST && c < 0x80 && shouldEscape(c, mode)) {
                    throw new ParseException("invalid character in host name");
                }
                i++;
            }
        }
        if (!need) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%') {
                out.write(unhex(s.charAt(i + 1)) << 4 | unhex(s.charAt(i + 2)));
                i += 3;
            } else if (c == '+' && mode == ENC_QUERY_COMPONENT) {
                out.write(' ');
                i++;
            } else {
                // Copy the character's own UTF-8 bytes.
                int cp = s.codePointAt(i);
                byte[] bytes = new String(Character.toChars(cp)).getBytes(UTF8);
                out.write(bytes, 0, bytes.length);
                i += Character.charCount(cp);
            }
        }
        return new String(out.toByteArray(), UTF8);
    }

    static String escape(String s, int mode) {
        byte[] bytes = s.getBytes(UTF8);
        StringBuilder b = null;
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i] & 0xff;
            boolean esc = c >= 0x80 || shouldEscape(c, mode) || (c == ' ' && mode == ENC_QUERY_COMPONENT);
            if (esc && b == null) {
                b = new StringBuilder(bytes.length * 2);
                b.append(new String(bytes, 0, i, UTF8));
            }
            if (b == null) continue;
            if (c == ' ' && mode == ENC_QUERY_COMPONENT) {
                b.append('+');
            } else if (esc) {
                b.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
            } else {
                b.append((char) c);
            }
        }
        return b == null ? s : b.toString();
    }
}
