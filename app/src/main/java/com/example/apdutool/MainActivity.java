package com.example.apdutool;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The phone acts as a contactless reader.
 *
 * Two modes, chosen with the radio buttons:
 *   Send APDU - transmit whatever hex you type and show the raw response
 *   Read tag  - run the full NFC Forum Type 4 read sequence and decode the NDEF
 */
public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback
{
    /* How long to wait for a response before giving up, in milliseconds. */
    private static final int TRANSCEIVE_TIMEOUT_MS = 3000;

    /* SELECT the NDEF Tag Application - a good first command for any Type 4 tag. */
    private static final String DEFAULT_APDU = "00 A4 04 00 07 D2 76 00 00 85 01 01 00";

    private NfcAdapter nfcAdapter;
    private EditText apduInput;
    private RadioGroup modeGroup;
    private TextView logView;
    private ScrollView logScroll;

    /* Written on the UI thread, read on the NFC thread, so keep it volatile. */
    private volatile boolean readTagMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        apduInput = findViewById(R.id.apdu_input);
        modeGroup = findViewById(R.id.mode_group);
        logView = findViewById(R.id.log_view);
        logScroll = findViewById(R.id.log_scroll);

        apduInput.setText(DEFAULT_APDU);

        modeGroup.setOnCheckedChangeListener((group, checkedId) ->
        {
            readTagMode = (checkedId == R.id.mode_read_tag);
            apduInput.setEnabled(!readTagMode);
        });

        Button clearButton = findViewById(R.id.clear_button);
        clearButton.setOnClickListener(v -> logView.setText(""));

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (nfcAdapter == null)
        {
            log("This device has no NFC hardware.");
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        if (nfcAdapter == null)
        {
            return;
        }

        if (!nfcAdapter.isEnabled())
        {
            Toast.makeText(this, "Please turn NFC on in Settings", Toast.LENGTH_LONG).show();
            return;
        }

        /*
         * Reader mode hands us the tag directly. Skipping the NDEF check keeps
         * the platform from reading the tag behind our back, which is what we
         * want when driving the exchange with our own APDUs.
         */
        int flags = NfcAdapter.FLAG_READER_NFC_A
                | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;

        nfcAdapter.enableReaderMode(this, this, flags, null);
        log("Reader ready - hold a tag against the phone.");
    }

    @Override
    protected void onPause()
    {
        super.onPause();

        if (nfcAdapter != null)
        {
            nfcAdapter.disableReaderMode(this);
        }
    }

    /**
     * Called on a background thread when a tag enters the field.
     * All view access has to be posted back to the UI thread.
     *
     * @param tag the tag that was discovered
     */
    @Override
    public void onTagDiscovered(Tag tag)
    {
        IsoDep isoDep = IsoDep.get(tag);

        if (isoDep == null)
        {
            log("Tag found, but it is not ISO-DEP so it cannot accept APDUs.");
            log("Type 4 tags always support ISO-DEP; this is probably a Type 1, 2 or 5 tag.\n");
            return;
        }

        try
        {
            isoDep.connect();
            isoDep.setTimeout(TRANSCEIVE_TIMEOUT_MS);

            log("=== Tag connected ===");
            log("UID:      " + HexUtil.toHex(tag.getId()));
            log("Max APDU: " + isoDep.getMaxTransceiveLength() + " bytes");

            byte[] historical = isoDep.getHistoricalBytes();

            if (historical != null && historical.length > 0)
            {
                log("Hist:     " + HexUtil.toHex(historical));
            }

            if (readTagMode)
            {
                readType4Tag(isoDep);
            }
            else
            {
                sendManualApdu(isoDep);
            }
        }
        catch (IOException e)
        {
            log("I/O error: " + e.getMessage() + " (tag moved away?)");
        }
        catch (SecurityException e)
        {
            log("Tag handle is stale: " + e.getMessage());
        }
        finally
        {
            try
            {
                isoDep.close();
            }
            catch (IOException ignored)
            {
                /* nothing useful to do here */
            }

            log("=== Tag released ===\n");
        }
    }

    /**
     * Send the APDU currently typed in the input box and log the response.
     *
     * @param isoDep the connected channel to the tag
     * @throws IOException if the tag stops responding
     */
    private void sendManualApdu(IsoDep isoDep) throws IOException
    {
        String typed = apduInput.getText().toString();
        byte[] command;

        try
        {
            command = HexUtil.toBytes(typed);
        }
        catch (IllegalArgumentException e)
        {
            log("Bad APDU: " + e.getMessage());
            return;
        }

        if (command.length < 4)
        {
            log("An APDU needs at least 4 bytes (CLA INS P1 P2).");
            return;
        }

        log("--> " + HexUtil.toHex(command));
        byte[] response = isoDep.transceive(command);
        log("<-- " + HexUtil.toHex(response));
        log(HexUtil.describeResponse(response));
    }

    /**
     * Run the Type 4 read sequence and print the decoded NDEF content.
     *
     * @param isoDep the connected channel to the tag
     * @throws IOException if the tag stops responding
     */
    private void readType4Tag(IsoDep isoDep) throws IOException
    {
        Type4TagReader reader = new Type4TagReader(isoDep, this::log);
        byte[] message = reader.readNdefMessage();

        if (message == null)
        {
            return;
        }

        log("NDEF message: " + HexUtil.toHex(message));
        log("\nDecoded:\n" + Type4TagReader.describeNdef(message));
    }

    /**
     * Append a timestamped line to the on-screen log, safe to call from any thread.
     *
     * @param message the text to append
     */
    private void log(final String message)
    {
        final String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());

        runOnUiThread(() ->
        {
            logView.append(stamp + "  " + message + "\n");
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
