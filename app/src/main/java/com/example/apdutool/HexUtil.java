package com.example.apdutool;

/**
 * Small helper for converting between hex strings and byte arrays.
 * APDUs are almost always written and logged as hex, so this gets used a lot.
 */
public final class HexUtil
{
    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    private HexUtil()
    {
        /* utility class, no instances */
    }

    /**
     * Convert a hex string into bytes.
     * Spaces, tabs and newlines are ignored, so "00 A4 04 00" is fine.
     *
     * @param hex hex text, e.g. "00A4040007A0000002471001"
     * @return the decoded bytes
     * @throws IllegalArgumentException if the text is not valid hex
     */
    public static byte[] toBytes(String hex)
    {
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");

        if (clean.length() % 2 != 0)
        {
            throw new IllegalArgumentException("Hex string must have an even number of digits");
        }

        byte[] out = new byte[clean.length() / 2];

        for (int i = 0; i < out.length; i++)
        {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }

        return out;
    }

    /**
     * Convert bytes into an upper-case hex string with a space between each byte.
     *
     * @param data bytes to format (may be null)
     * @return formatted hex text, or "" when data is null or empty
     */
    public static String toHex(byte[] data)
    {
        if (data == null || data.length == 0)
        {
            return "";
        }

        StringBuilder sb = new StringBuilder(data.length * 3);

        for (int i = 0; i < data.length; i++)
        {
            int v = data[i] & 0xFF;
            sb.append(HEX_CHARS[v >>> 4]);
            sb.append(HEX_CHARS[v & 0x0F]);

            if (i < data.length - 1)
            {
                sb.append(' ');
            }
        }

        return sb.toString();
    }

    /**
     * Split a card response into its data part and its 2-byte status word.
     *
     * @param response full response APDU from the card
     * @return human readable summary, e.g. "Data: 6F 20 ...  SW: 9000 (OK)"
     */
    public static String describeResponse(byte[] response)
    {
        if (response == null || response.length < 2)
        {
            return "Malformed response (fewer than 2 bytes)";
        }

        int swLen = response.length;
        int sw1 = response[swLen - 2] & 0xFF;
        int sw2 = response[swLen - 1] & 0xFF;

        byte[] body = new byte[swLen - 2];
        System.arraycopy(response, 0, body, 0, body.length);

        String meaning;

        if (sw1 == 0x90 && sw2 == 0x00)
        {
            meaning = "OK";
        }
        else if (sw1 == 0x61)
        {
            meaning = sw2 + " more bytes available (use GET RESPONSE)";
        }
        else if (sw1 == 0x6A && sw2 == 0x82)
        {
            meaning = "File or application not found";
        }
        else if (sw1 == 0x6D)
        {
            meaning = "Instruction not supported";
        }
        else if (sw1 == 0x6E)
        {
            meaning = "Class not supported";
        }
        else if (sw1 == 0x67)
        {
            meaning = "Wrong length - check your Lc byte";
        }
        else if (sw1 == 0x69 && sw2 == 0x82)
        {
            meaning = "Security status not satisfied - tag is write protected";
        }
        else if (sw1 == 0x69 && sw2 == 0x85)
        {
            meaning = "Conditions not satisfied - select the file first";
        }
        else if (sw1 == 0x6B && sw2 == 0x00)
        {
            meaning = "Wrong P1/P2 - offset is past the end of the file";
        }
        else if (sw1 == 0x65 && sw2 == 0x81)
        {
            meaning = "Memory failure - write did not stick";
        }
        else
        {
            meaning = "see ISO 7816-4";
        }

        return "Data: " + (body.length == 0 ? "(none)" : toHex(body))
                + "\nSW:   " + String.format("%02X%02X", sw1, sw2) + "  (" + meaning + ")";
    }
}
