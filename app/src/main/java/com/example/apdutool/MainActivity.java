package com.example.apdutool;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
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
 * The editor holds one APDU per line. Every line is sent in order on a single
 * tap, over one connection, so a SELECT on line 1 is still in effect when
 * line 2 runs. Blank lines are skipped and anything after # or // is a comment.
 *
 * Two modes:
 *   Send APDUs     - run whatever is in the editor
 *   Read Type 4    - run the built in NDEF read and decode the message
 */
public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback
{
    /* How long to wait for a response before giving up, in milliseconds. */
    private static final int TRANSCEIVE_TIMEOUT_MS = 3000;

    private NfcAdapter nfcAdapter;
    private EditText apduInput;
    private Spinner presetSpinner;
    private TextView logView;
    private ScrollView logScroll;

    /* Written on the UI thread, read on the NFC thread, so keep it volatile. */
    private volatile boolean readTagMode = false;

    /* Snapshot of the editor text, refreshed whenever the user edits it. */
    private volatile String scriptText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        apduInput = findViewById(R.id.apdu_input);
        presetSpinner = findViewById(R.id.preset_spinner);
        logView = findViewById(R.id.log_view);
        logScroll = findViewById(R.id.log_scroll);

        /* Fill the preset picker. */
        ArrayAdapter<ApduPreset> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, ApduPreset.all());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);

        /* Start with something useful already loaded. */
        apduInput.setText(ApduPreset.commandFileSequence());
        scriptText = apduInput.getText().toString();

        apduInput.addTextChangedListener(new SimpleTextWatcher()
        {
            @Override
            public void onTextChanged(String text)
            {
                scriptText = text;
            }
        });

        RadioGroup modeGroup = findViewById(R.id.mode_group);
        modeGroup.setOnCheckedChangeListener((group, checkedId) ->
        {
            readTagMode = (checkedId == R.id.mode_read_tag);
            apduInput.setEnabled(!readTagMode);
            presetSpinner.setEnabled(!readTagMode);
        });

        Button addButton = findViewById(R.id.add_button);
        addButton.setOnClickListener(v -> insertSelectedPreset());

        Button sequenceButton = findViewById(R.id.sequence_button);
        sequenceButton.setOnClickListener(v ->
        {
            apduInput.setText(ApduPreset.type4Sequence());
            scriptText = apduInput.getText().toString();
        });

        Button writeSequenceButton = findViewById(R.id.write_sequence_button);
        writeSequenceButton.setOnClickListener(v ->
        {
            apduInput.setText(ApduPreset.writeSequence());
            scriptText = apduInput.getText().toString();
        });

        Button commandSequenceButton = findViewById(R.id.command_sequence_button);
        commandSequenceButton.setOnClickListener(v ->
        {
            apduInput.setText(ApduPreset.commandFileSequence());
            scriptText = apduInput.getText().toString();
        });

        Button clearInputButton = findViewById(R.id.clear_input_button);
        clearInputButton.setOnClickListener(v ->
        {
            apduInput.setText("");
            scriptText = "";
        });

        Button clearLogButton = findViewById(R.id.clear_log_button);
        clearLogButton.setOnClickListener(v -> logView.setText(""));

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (nfcAdapter == null)
        {
            log("This device has no NFC hardware.");
        }
    }

    /**
     * Append the APDU currently chosen in the picker onto its own line.
     */
    private void insertSelectedPreset()
    {
        ApduPreset preset = (ApduPreset) presetSpinner.getSelectedItem();

        if (preset == null)
        {
            return;
        }

        String current = apduInput.getText().toString();
        StringBuilder sb = new StringBuilder(current);

        if (current.length() > 0 && !current.endsWith("\n"))
        {
            sb.append('\n');
        }

        sb.append(preset.getHex()).append("   # ").append(preset).append('\n');

        apduInput.setText(sb.toString());
        apduInput.setSelection(apduInput.getText().length());
        scriptText = sb.toString();
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
            log("Probably a Type 1, 2 or 5 tag - those use their own command set.\n");
            return;
        }

        try
        {
            isoDep.connect();
            isoDep.setTimeout(TRANSCEIVE_TIMEOUT_MS);

            log("=== Tag connected ===");
            log("UID:      " + HexUtil.toHex(tag.getId()));
            log("Max APDU: " + isoDep.getMaxTransceiveLength() + " bytes");

            if (readTagMode)
            {
                readType4Tag(isoDep);
            }
            else
            {
                runScript(isoDep);
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
     * Send every command in the editor, in order, over the one open
     * connection. Directives such as write and writemsg are expanded first.
     * Stops early on failure, because later steps usually depend on earlier
     * ones having worked.
     *
     * @param isoDep the connected channel to the tag
     * @throws IOException if the tag stops responding
     */
    private void runScript(IsoDep isoDep) throws IOException
    {
        ApduScript.Result parsed = ApduScript.parse(scriptText);

        for (String problem : parsed.problems)
        {
            log(problem);
        }

        if (parsed.steps.isEmpty())
        {
            log("Nothing to send - the editor has no valid commands.");
            return;
        }

        log("Sending " + parsed.steps.size() + " APDU(s) on this tap.\n");

        for (int i = 0; i < parsed.steps.size(); i++)
        {
            ApduScript.Step step = parsed.steps.get(i);

            log("[" + (i + 1) + "/" + parsed.steps.size() + "] " + step.label);
            log("--> " + HexUtil.toHex(step.command));

            byte[] response = isoDep.transceive(step.command);

            /* 61 xx means more data is waiting, so fetch it. */
            if (response.length == 2 && (response[0] & 0xFF) == 0x61)
            {
                byte[] getResponse = { 0x00, (byte) 0xC0, 0x00, 0x00, response[1] };
                log("--> GET RESPONSE  " + HexUtil.toHex(getResponse));
                response = isoDep.transceive(getResponse);
            }

            log("<-- " + HexUtil.toHex(response));
            log(HexUtil.describeResponse(response) + "\n");

            if (!isSuccess(response) && i < parsed.steps.size() - 1)
            {
                log("Stopping here - later commands depend on this one succeeding.");
                break;
            }
        }
    }

    /**
     * Run the built in Type 4 read and print the decoded NDEF content.
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
     * Check whether a response APDU ends in 9000.
     *
     * @param response the response APDU
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
     * Append a timestamped line to the on-screen log, safe from any thread.
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
