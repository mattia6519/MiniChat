package com.example.minichat;

final class PhoneUtils {
    private PhoneUtils() {
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        String normalized = digits.toString();
        if (normalized.startsWith("00") && normalized.length() > 2) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    static String normalizeInternational(String value, String defaultCountryCode) {
        String digits = normalize(value);
        if (digits.isEmpty()) {
            return "";
        }
        String countryCode = countryCodeDigits(defaultCountryCode);
        if (countryCode.isEmpty()) {
            return digits;
        }

        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("+") || digits.startsWith(countryCode)) {
            return digits;
        }
        return countryCode + digits;
    }

    static String countryCodeDigits(String value) {
        String digits = normalize(value);
        if (digits.startsWith("00") && digits.length() > 2) {
            digits = digits.substring(2);
        }
        return digits;
    }

    static String withPlus(String normalizedInternationalNumber) {
        String digits = normalize(normalizedInternationalNumber);
        return digits.isEmpty() ? "" : "+" + digits;
    }
}
