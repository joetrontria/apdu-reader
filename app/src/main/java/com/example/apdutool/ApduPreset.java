package com.example.apdutool;

/**
 * A named command APDU that the user can drop into the editor instead of
 * typing it out. Kept as plain text so it stays editable after insertion.
 */
public class ApduPreset
{
    private final String name;
    private final String hex;

    /**
     * @param name short label shown in the picker
     * @param hex  the command APDU as hex text
     */
    public ApduPreset(String name, String hex)
    {
        this.name = name;
        this.hex = hex;
    }

    /**
     * @return the command APDU as hex text
     */
    public String getHex()
    {
        return hex;
    }

    /**
     * @return the label, which is also what the picker displays
     */
    @Override
    public String toString()
    {
        return name;
    }

    /**
     * Every preset offered in the picker, in a sensible order.
     *
     * @return the preset list
     */
    public static ApduPreset[] all()
    {
        return new ApduPreset[]
                {
                        new ApduPreset("SELECT NDEF application (Le)",
                                "00 A4 04 00 07 D2 76 00 00 85 01 01 00"),

                        new ApduPreset("SELECT NDEF application (no Le)",
                                "00 A4 04 00 07 D2 76 00 00 85 01 01"),

                        new ApduPreset("SELECT CC file (E103)",
                                "00 A4 00 0C 02 E1 03"),

                        new ApduPreset("SELECT NDEF file (1000)",
                                "00 A4 00 0C 02 10 00"),

                        new ApduPreset("SELECT command file (1001)",
                                "00 A4 00 0C 02 10 01"),

                        new ApduPreset("READ BINARY - CC, 23 bytes",
                                "00 B0 00 00 17"),

                        new ApduPreset("READ BINARY - 16 bytes at offset 0",
                                "00 B0 00 00 10"),

                        new ApduPreset("READ BINARY - NLEN, 2 bytes",
                                "00 B0 00 00 02"),

                        new ApduPreset("W. Command frame at offset 0",
                                "write 0000 02 01 01"),

                        new ApduPreset("W. UPDATE BINARY at offset 2",
                                "write 0002 04 01 01"),

                        new ApduPreset("W. Write NDEF message (safe)",
                                "writemsg 04 01 01"),

                        new ApduPreset("W. Set NLEN",
                                "nlen 3"),

                        new ApduPreset("GET DATA (probe)",
                                "00 CA 00 00 00"),

                        new ApduPreset("SELECT default app (no AID)",
                                "00 A4 04 00 00"),
                };
    }

    /**
     * Send a raw command frame to the proprietary command file 0x1001.
     * That file is not an NDEF file, so the payload goes at offset 0 with
     * no NLEN header - use write, never writemsg.
     *
     * @return a multi-line script ready to drop into the editor
     */
    public static String commandFileSequence()
    {
        return "# Send a command frame to file 1001 - edit the bytes on the last line\n"
                + "00 A4 04 00 07 D2 76 00 00 85 01 01 00   # SELECT NDEF application\n"
                + "00 A4 00 0C 02 10 01                     # SELECT command file 1001\n"
                + "write 0000 02 01 01                      # UPDATE BINARY at offset 0\n"
                + "\n"
                + "# If the SELECT above returns 6700 or 6A86, drop its trailing 00 (Le).\n"
                + "# Device must be in production mode or the frame is rejected.\n";
    }

    /**
     * A ready made write chain. Selecting the file first is mandatory, and
     * writemsg handles the NLEN dance and counts Lc for you.
     *
     * @return a multi-line script ready to drop into the editor
     */
    public static String writeSequence()
    {
        return "# Write sequence - edit the bytes on the last line\n"
                + "00 A4 04 00 07 D2 76 00 00 85 01 01 00   # SELECT NDEF application\n"
                + "00 A4 00 0C 02 E1 03                     # SELECT CC file\n"
                + "00 B0 00 00 17                           # READ CC - byte 15 is write access\n"
                + "00 A4 00 0C 02 10 00                     # SELECT NDEF file 1000\n"
                + "writemsg 04 01 01                        # your bytes - Lc and NLEN are automatic\n"
                + "00 B0 00 00 10                           # read it back to confirm\n";
    }

    /**
     * The five step Type 4 read chain, as editor text with comments.
     *
     * @return a multi-line script ready to drop into the editor
     */
    public static String type4Sequence()
    {
        return "# Type 4 read sequence - all steps run on one tap\n"
                + "00 A4 04 00 07 D2 76 00 00 85 01 01 00   # SELECT NDEF application\n"
                + "00 A4 00 0C 02 E1 03                     # SELECT CC file\n"
                + "00 B0 00 00 17                           # READ BINARY the whole CC\n"
                + "00 A4 00 0C 02 10 00                     # SELECT NDEF file 1000\n"
                + "00 B0 00 00 02                           # READ NLEN\n"
                + "00 B0 00 02 20                           # READ 32 bytes of NDEF\n";
    }
}
