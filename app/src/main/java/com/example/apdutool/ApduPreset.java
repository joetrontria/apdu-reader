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
                        new ApduPreset("1. SELECT NDEF application",
                                "00 A4 04 00 07 D2 76 00 00 85 01 01 00"),

                        new ApduPreset("2. SELECT CC file (E103)",
                                "00 A4 00 0C 02 E1 03"),

                        new ApduPreset("3. READ BINARY - CC, 15 bytes",
                                "00 B0 00 00 0F"),

                        new ApduPreset("4. SELECT NDEF file (E104)",
                                "00 A4 00 0C 02 E1 04"),

                        new ApduPreset("5. READ BINARY - NLEN, 2 bytes",
                                "00 B0 00 00 02"),

                        new ApduPreset("6. READ BINARY - NDEF from offset 2",
                                "00 B0 00 02 20"),

                        new ApduPreset("GET DATA (probe)",
                                "00 CA 00 00 00"),

                        new ApduPreset("SELECT default app (no AID)",
                                "00 A4 04 00 00"),

                        new ApduPreset("SELECT PPSE (payment cards)",
                                "00 A4 04 00 0E 32 50 41 59 2E 53 59 53 2E 44 44 46 30 31 00"),
                };
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
                + "00 B0 00 00 0F                           # READ BINARY the CC\n"
                + "00 A4 00 0C 02 E1 04                     # SELECT NDEF file\n"
                + "00 B0 00 00 02                           # READ NLEN\n"
                + "00 B0 00 02 20                           # READ 32 bytes of NDEF\n";
    }
}
