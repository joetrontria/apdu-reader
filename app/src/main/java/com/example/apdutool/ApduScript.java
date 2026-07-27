package com.example.apdutool;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the text in the editor into a list of command APDUs.
 *
 * A line is either raw hex, which is sent exactly as typed, or one of the
 * directives below, which get expanded into properly formed UPDATE BINARY
 * commands so the Lc byte never has to be counted by hand.
 *
 *   write <offset> <data...>   UPDATE BINARY of <data> at <offset>
 *   nlen <count>               set the 2 byte NDEF length field
 *   writemsg <data...>         zero NLEN, write <data> at offset 2, set NLEN
 *
 * Offsets are hex, counts are decimal. Anything after # or // is a comment.
 */
public final class ApduScript
{
    /** One command APDU together with the text that produced it. */
    public static class Step
    {
        public final byte[] command;
        public final String label;

        Step(byte[] command, String label)
        {
            this.command = command;
            this.label = label;
        }
    }

    /** Parsed script plus any complaints about lines that could not be used. */
    public static class Result
    {
        public final List<Step> steps = new ArrayList<>();
        public final List<String> problems = new ArrayList<>();
    }

    /* Most tags accept far more, but 200 data bytes per write is safe. */
    private static final int MAX_WRITE_CHUNK = 200;

    private ApduScript()
    {
        /* utility class, no instances */
    }

    /**
     * Parse the editor text into commands ready to send.
     *
     * @param script the full multi-line editor contents
     * @return the parsed steps and any per-line problems
     */
    public static Result parse(String script)
    {
        Result result = new Result();

        if (script == null)
        {
            return result;
        }

        String[] lines = script.split("\n");

        for (int i = 0; i < lines.length; i++)
        {
            int lineNumber = i + 1;
            String line = stripComment(lines[i]);

            if (line.isEmpty())
            {
                continue;
            }

            String lower = line.toLowerCase();

            try
            {
                if (lower.startsWith("writemsg "))
                {
                    addWriteMessage(result, line.substring(9), lineNumber);
                }
                else if (lower.startsWith("write "))
                {
                    addWrite(result, line.substring(6), lineNumber);
                }
                else if (lower.startsWith("nlen "))
                {
                    addNlen(result, line.substring(5).trim(), lineNumber);
                }
                else
                {
                    addRaw(result, line, lineNumber);
                }
            }
            catch (IllegalArgumentException e)
            {
                result.problems.add("Line " + lineNumber + ": " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Handle a plain hex line, sent to the tag exactly as written.
     *
     * @param result     collector for steps and problems
     * @param line       the hex text
     * @param lineNumber line number for the label
     */
    private static void addRaw(Result result, String line, int lineNumber)
    {
        byte[] command = HexUtil.toBytes(line);

        if (command.length < 4)
        {
            throw new IllegalArgumentException(
                    "an APDU needs at least 4 bytes (CLA INS P1 P2)");
        }

        result.steps.add(new Step(command, "line " + lineNumber));
    }

    /**
     * Handle "write &lt;offset&gt; &lt;data...&gt;" by building one or more
     * UPDATE BINARY commands with the correct Lc.
     *
     * @param result     collector for steps and problems
     * @param arguments  everything after the write keyword
     * @param lineNumber line number for the label
     */
    private static void addWrite(Result result, String arguments, int lineNumber)
    {
        String trimmed = arguments.trim();
        int split = trimmed.indexOf(' ');

        if (split < 0)
        {
            throw new IllegalArgumentException("write needs an offset and some data");
        }

        int offset = parseOffset(trimmed.substring(0, split));
        byte[] data = HexUtil.toBytes(trimmed.substring(split));

        if (data.length == 0)
        {
            throw new IllegalArgumentException("write needs at least one data byte");
        }

        appendWrites(result, offset, data, lineNumber);
    }

    /**
     * Handle "writemsg &lt;data...&gt;" - the safe three part write that
     * clears NLEN first so a reader never sees a half written message.
     *
     * @param result     collector for steps and problems
     * @param arguments  everything after the writemsg keyword
     * @param lineNumber line number for the label
     */
    private static void addWriteMessage(Result result, String arguments, int lineNumber)
    {
        byte[] data = HexUtil.toBytes(arguments);

        if (data.length == 0)
        {
            throw new IllegalArgumentException("writemsg needs at least one data byte");
        }

        if (data.length > 0xFFFF)
        {
            throw new IllegalArgumentException("message is too long for a 2 byte NLEN");
        }

        result.steps.add(new Step(buildNlen(0), "line " + lineNumber + " - NLEN = 0"));
        appendWrites(result, 2, data, lineNumber);
        result.steps.add(new Step(buildNlen(data.length),
                "line " + lineNumber + " - NLEN = " + data.length));
    }

    /**
     * Handle "nlen &lt;count&gt;" by writing the 2 byte length field.
     *
     * @param result     collector for steps and problems
     * @param argument   the decimal count
     * @param lineNumber line number for the label
     */
    private static void addNlen(Result result, String argument, int lineNumber)
    {
        int count;

        try
        {
            count = Integer.parseInt(argument);
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("nlen needs a decimal count, got '" + argument + "'");
        }

        if (count < 0 || count > 0xFFFF)
        {
            throw new IllegalArgumentException("nlen must be between 0 and 65535");
        }

        result.steps.add(new Step(buildNlen(count), "line " + lineNumber + " - NLEN = " + count));
    }

    /**
     * Split data into chunks and add an UPDATE BINARY for each one.
     *
     * @param result     collector for steps
     * @param offset     starting byte offset in the file
     * @param data       the bytes to write
     * @param lineNumber line number for the label
     */
    private static void appendWrites(Result result, int offset, byte[] data, int lineNumber)
    {
        int written = 0;

        while (written < data.length)
        {
            int size = Math.min(data.length - written, MAX_WRITE_CHUNK);
            byte[] chunk = new byte[size];
            System.arraycopy(data, written, chunk, 0, size);

            int chunkOffset = offset + written;
            result.steps.add(new Step(buildUpdateBinary(chunkOffset, chunk),
                    "line " + lineNumber + " - write " + size
                            + " byte(s) at offset " + chunkOffset));

            written += size;
        }
    }

    /**
     * Build an UPDATE BINARY command APDU.
     *
     * @param offset byte offset in the currently selected file
     * @param data   the bytes to write, 1 to 255 of them
     * @return the command APDU
     */
    public static byte[] buildUpdateBinary(int offset, byte[] data)
    {
        if (offset < 0 || offset > 0x7FFF)
        {
            throw new IllegalArgumentException("offset must be between 0 and 7FFF");
        }

        if (data.length > 0xFF)
        {
            throw new IllegalArgumentException("one UPDATE BINARY carries at most 255 bytes");
        }

        byte[] apdu = new byte[5 + data.length];
        apdu[0] = 0x00;                          /* CLA */
        apdu[1] = (byte) 0xD6;                   /* INS = UPDATE BINARY */
        apdu[2] = (byte) ((offset >> 8) & 0xFF); /* P1 = offset high */
        apdu[3] = (byte) (offset & 0xFF);        /* P2 = offset low  */
        apdu[4] = (byte) data.length;            /* Lc, counted for you */
        System.arraycopy(data, 0, apdu, 5, data.length);
        return apdu;
    }

    /**
     * Build the UPDATE BINARY that sets the 2 byte NLEN field at offset 0.
     *
     * @param count the NDEF message length to record
     * @return the command APDU
     */
    private static byte[] buildNlen(int count)
    {
        byte[] value = { (byte) ((count >> 8) & 0xFF), (byte) (count & 0xFF) };
        return buildUpdateBinary(0, value);
    }

    /**
     * Parse an offset written in hex, with or without a 0x prefix.
     *
     * @param text the offset text, e.g. "0002" or "0x02"
     * @return the offset as an int
     */
    private static int parseOffset(String text)
    {
        String clean = text.trim();

        if (clean.toLowerCase().startsWith("0x"))
        {
            clean = clean.substring(2);
        }

        try
        {
            return Integer.parseInt(clean, 16);
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("bad offset '" + text + "', expected hex");
        }
    }

    /**
     * Remove a trailing comment introduced by # or // and trim the result.
     *
     * @param line one raw line from the editor
     * @return the line with any comment and surrounding space removed
     */
    private static String stripComment(String line)
    {
        int hash = line.indexOf('#');
        int slashes = line.indexOf("//");
        int cut = -1;

        if (hash >= 0)
        {
            cut = hash;
        }

        if (slashes >= 0 && (cut < 0 || slashes < cut))
        {
            cut = slashes;
        }

        if (cut >= 0)
        {
            line = line.substring(0, cut);
        }

        return line.trim();
    }
}
