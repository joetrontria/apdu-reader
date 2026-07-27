package com.example.apdutool;

import android.nfc.tech.IsoDep;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * Reads an NFC Forum Type 4 tag using plain ISO 7816-4 APDUs.
 *
 * The sequence is always the same:
 *   1. SELECT the NDEF Tag Application by AID  D2 76 00 00 85 01 01
 *   2. SELECT the Capability Container file    (file ID E1 03)
 *   3. READ BINARY the CC, which tells us the NDEF file ID and size limits
 *   4. SELECT the NDEF file using that ID
 *   5. READ BINARY 2 bytes -> NLEN, the length of the NDEF message
 *   6. READ BINARY NLEN bytes starting at offset 2 -> the NDEF message itself
 */
public class Type4TagReader
{
    /** Callback so the caller can print each step as it happens. */
    public interface Logger
    {
        void onLine(String text);
    }

    /* NDEF Tag Application AID defined by the NFC Forum Type 4 spec. */
    private static final byte[] NDEF_AID =
            { (byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01 };

    /* Capability Container file identifier. */
    private static final byte[] CC_FILE_ID = { (byte) 0xE1, 0x03 };

    /* Abbreviation table for NDEF URI records, see the NFC Forum URI RTD. */
    private static final String[] URI_PREFIX =
            {
                    "", "http://www.", "https://www.", "http://", "https://", "tel:",
                    "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
                    "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:",
                    "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:",
                    "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
                    "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
                    "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:"
            };

    private final IsoDep isoDep;
    private final Logger logger;

    /* Largest Le we may ask for in one READ BINARY, taken from the CC. */
    private int maxReadLength = 0x3B;

    /**
     * @param isoDep an already-connected IsoDep channel
     * @param logger where to send progress lines
     */
    public Type4TagReader(IsoDep isoDep, Logger logger)
    {
        this.isoDep = isoDep;
        this.logger = logger;
    }

    /**
     * Run the whole Type 4 read sequence and return the raw NDEF message.
     *
     * @return the NDEF message bytes, or null if the tag is not Type 4 formatted
     * @throws IOException if the tag stops responding partway through
     */
    public byte[] readNdefMessage() throws IOException
    {
        /* Step 1: select the NDEF application. */
        byte[] response = send(buildSelectByName(NDEF_AID), "SELECT NDEF application");

        if (!isSuccess(response))
        {
            logger.onLine("This tag does not expose an NDEF application - not a Type 4 tag.");
            return null;
        }

        /* Step 2 and 3: select and read the Capability Container. */
        response = send(buildSelectByFileId(CC_FILE_ID), "SELECT CC file (E103)");

        if (!isSuccess(response))
        {
            logger.onLine("Capability Container could not be selected.");
            return null;
        }

        byte[] cc = readBinary(0, 15);

        if (cc == null || cc.length < 15)
        {
            logger.onLine("Capability Container is too short to be valid.");
            return null;
        }

        int mLe = ((cc[3] & 0xFF) << 8) | (cc[4] & 0xFF);
        byte[] ndefFileId = { cc[9], cc[10] };
        int maxNdefSize = ((cc[11] & 0xFF) << 8) | (cc[12] & 0xFF);
        int readAccess = cc[13] & 0xFF;

        /* Leave 2 bytes of headroom for the status word. */
        maxReadLength = Math.max(1, Math.min(mLe - 2, 0xFF));

        logger.onLine("CC: NDEF file ID " + HexUtil.toHex(ndefFileId)
                + ", max size " + maxNdefSize + " bytes"
                + ", read access " + (readAccess == 0x00 ? "granted" : "restricted"));

        /* Step 4: select the NDEF file the CC pointed us at. */
        response = send(buildSelectByFileId(ndefFileId), "SELECT NDEF file");

        if (!isSuccess(response))
        {
            logger.onLine("NDEF file could not be selected.");
            return null;
        }

        /* Step 5: the first two bytes hold NLEN, the message length. */
        byte[] nlenBytes = readBinary(0, 2);

        if (nlenBytes == null || nlenBytes.length < 2)
        {
            logger.onLine("Could not read NLEN.");
            return null;
        }

        int nlen = ((nlenBytes[0] & 0xFF) << 8) | (nlenBytes[1] & 0xFF);
        logger.onLine("NLEN = " + nlen + " bytes");

        if (nlen == 0)
        {
            logger.onLine("Tag is formatted but empty.");
            return new byte[0];
        }

        /* Step 6: read the message, in chunks if it is longer than one APDU. */
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int offset = 2;
        int remaining = nlen;

        while (remaining > 0)
        {
            int chunk = Math.min(remaining, maxReadLength);
            byte[] part = readBinary(offset, chunk);

            if (part == null || part.length == 0)
            {
                logger.onLine("Read stopped early at offset " + offset + ".");
                break;
            }

            message.write(part, 0, part.length);
            offset += part.length;
            remaining -= part.length;
        }

        return message.toByteArray();
    }

    /**
     * Turn an NDEF message into readable text, handling the two common record
     * types (Text and URI) and dumping anything else as hex.
     *
     * @param message raw NDEF message bytes
     * @return a multi-line human readable description
     */
    public static String describeNdef(byte[] message)
    {
        if (message == null || message.length == 0)
        {
            return "(no NDEF content)";
        }

        StringBuilder out = new StringBuilder();
        int pos = 0;
        int recordNumber = 1;

        while (pos < message.length)
        {
            int header = message[pos] & 0xFF;
            boolean shortRecord = (header & 0x10) != 0;
            boolean hasIdLength = (header & 0x08) != 0;
            int tnf = header & 0x07;
            pos++;

            if (pos >= message.length)
            {
                break;
            }

            int typeLength = message[pos] & 0xFF;
            pos++;

            int payloadLength;

            if (shortRecord)
            {
                payloadLength = message[pos] & 0xFF;
                pos += 1;
            }
            else
            {
                payloadLength = ((message[pos] & 0xFF) << 24)
                        | ((message[pos + 1] & 0xFF) << 16)
                        | ((message[pos + 2] & 0xFF) << 8)
                        | (message[pos + 3] & 0xFF);
                pos += 4;
            }

            int idLength = 0;

            if (hasIdLength)
            {
                idLength = message[pos] & 0xFF;
                pos++;
            }

            if (pos + typeLength + idLength + payloadLength > message.length)
            {
                out.append("Record ").append(recordNumber).append(": truncated\n");
                break;
            }

            byte[] type = new byte[typeLength];
            System.arraycopy(message, pos, type, 0, typeLength);
            pos += typeLength + idLength;

            byte[] payload = new byte[payloadLength];
            System.arraycopy(message, pos, payload, 0, payloadLength);
            pos += payloadLength;

            out.append("Record ").append(recordNumber).append(" (TNF ").append(tnf)
                    .append(", type '").append(asAscii(type)).append("'):\n  ")
                    .append(decodePayload(type, payload)).append('\n');

            recordNumber++;

            /* Bit 6 of the header is ME, the "last record" flag. */
            if ((header & 0x40) != 0)
            {
                break;
            }
        }

        return out.toString().trim();
    }

    /**
     * Decode a single record payload based on its type field.
     *
     * @param type    the record type bytes, e.g. 'T' or 'U'
     * @param payload the record payload bytes
     * @return decoded text, or a hex dump when the type is unknown
     */
    private static String decodePayload(byte[] type, byte[] payload)
    {
        if (type.length == 1 && type[0] == 'T' && payload.length > 0)
        {
            int status = payload[0] & 0xFF;
            int langLength = status & 0x3F;
            String charset = ((status & 0x80) != 0) ? "UTF-16" : "UTF-8";
            int textStart = 1 + langLength;

            if (textStart <= payload.length)
            {
                try
                {
                    return new String(payload, textStart, payload.length - textStart, charset);
                }
                catch (UnsupportedEncodingException e)
                {
                    return HexUtil.toHex(payload);
                }
            }
        }

        if (type.length == 1 && type[0] == 'U' && payload.length > 0)
        {
            int prefixCode = payload[0] & 0xFF;
            String prefix = (prefixCode < URI_PREFIX.length) ? URI_PREFIX[prefixCode] : "";
            return prefix + asAscii(payload, 1, payload.length - 1);
        }

        return HexUtil.toHex(payload);
    }

    /**
     * Build a SELECT (by name / AID) APDU.
     *
     * @param aid application identifier to select
     * @return the command APDU bytes
     */
    private static byte[] buildSelectByName(byte[] aid)
    {
        byte[] apdu = new byte[6 + aid.length];
        apdu[0] = 0x00;               /* CLA */
        apdu[1] = (byte) 0xA4;        /* INS = SELECT */
        apdu[2] = 0x04;               /* P1  = select by name */
        apdu[3] = 0x00;               /* P2  = first or only occurrence */
        apdu[4] = (byte) aid.length;  /* Lc  */
        System.arraycopy(aid, 0, apdu, 5, aid.length);
        apdu[apdu.length - 1] = 0x00; /* Le  */
        return apdu;
    }

    /**
     * Build a SELECT (by file identifier) APDU.
     *
     * @param fileId the 2-byte file identifier
     * @return the command APDU bytes
     */
    private static byte[] buildSelectByFileId(byte[] fileId)
    {
        byte[] apdu = new byte[5 + fileId.length];
        apdu[0] = 0x00;               /* CLA */
        apdu[1] = (byte) 0xA4;        /* INS = SELECT */
        apdu[2] = 0x00;               /* P1  = select by file ID */
        apdu[3] = 0x0C;               /* P2  = no response data */
        apdu[4] = (byte) fileId.length;
        System.arraycopy(fileId, 0, apdu, 5, fileId.length);
        return apdu;
    }

    /**
     * Issue a READ BINARY for the currently selected file.
     *
     * @param offset byte offset into the file
     * @param length number of bytes to read, 1 to 255
     * @return the data read, without the status word, or null on failure
     * @throws IOException if the tag stops responding
     */
    private byte[] readBinary(int offset, int length) throws IOException
    {
        byte[] apdu =
                {
                        0x00,                          /* CLA */
                        (byte) 0xB0,                   /* INS = READ BINARY */
                        (byte) ((offset >> 8) & 0xFF), /* P1 = offset high */
                        (byte) (offset & 0xFF),        /* P2 = offset low  */
                        (byte) length                  /* Le */
                };

        byte[] response = send(apdu, "READ BINARY offset " + offset + " len " + length);

        if (!isSuccess(response))
        {
            return null;
        }

        byte[] data = new byte[response.length - 2];
        System.arraycopy(response, 0, data, 0, data.length);
        return data;
    }

    /**
     * Send one APDU, log both directions, and follow up on a 61xx status word
     * by issuing GET RESPONSE automatically.
     *
     * @param apdu  command APDU to send
     * @param label short description for the log
     * @return the response APDU including its status word
     * @throws IOException if the tag stops responding
     */
    private byte[] send(byte[] apdu, String label) throws IOException
    {
        logger.onLine("--> " + label + "\n    " + HexUtil.toHex(apdu));
        byte[] response = isoDep.transceive(apdu);
        logger.onLine("<-- " + HexUtil.toHex(response));

        /* 61 xx means "xx more bytes are waiting", so fetch them. */
        if (response.length == 2 && (response[0] & 0xFF) == 0x61)
        {
            byte[] getResponse = { 0x00, (byte) 0xC0, 0x00, 0x00, response[1] };
            logger.onLine("--> GET RESPONSE\n    " + HexUtil.toHex(getResponse));
            response = isoDep.transceive(getResponse);
            logger.onLine("<-- " + HexUtil.toHex(response));
        }

        return response;
    }

    /**
     * Check whether a response APDU ends in 9000.
     *
     * @param response response APDU to test
     * @return true when the status word is 9000
     */
    private static boolean isSuccess(byte[] response)
    {
        return response != null
                && response.length >= 2
                && (response[response.length - 2] & 0xFF) == 0x90
                && (response[response.length - 1] & 0xFF) == 0x00;
    }

    /**
     * Render bytes as printable ASCII, replacing anything unprintable with a dot.
     *
     * @param data bytes to render
     * @return the printable string
     */
    private static String asAscii(byte[] data)
    {
        return asAscii(data, 0, data.length);
    }

    /**
     * Render part of a byte array as printable ASCII.
     *
     * @param data   source bytes
     * @param start  first index to include
     * @param length how many bytes to include
     * @return the printable string
     */
    private static String asAscii(byte[] data, int start, int length)
    {
        StringBuilder sb = new StringBuilder();

        for (int i = start; i < start + length && i < data.length; i++)
        {
            int c = data[i] & 0xFF;
            sb.append((c >= 0x20 && c < 0x7F) ? (char) c : '.');
        }

        return sb.toString();
    }
}
