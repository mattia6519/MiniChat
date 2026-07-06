package com.example.minichat;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private EditText hostInput;
    private EditText portInput;
    private CheckBox tlsInput;
    private CheckBox centralInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20));
        root.setBackgroundResource(R.color.bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_media_previous);
        back.setContentDescription("Indietro");
        back.setBackgroundColor(0x00000000);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        TextView title = Ui.title(this, "Impostazioni");
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView info = Ui.subtitle(this, "Configura indirizzo e porta del backend.");
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        infoParams.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 20));
        root.addView(info, infoParams);

        hostInput = Ui.input(this, "Host o IP");
        hostInput.setText(Prefs.getCustomHost(this));
        root.addView(hostInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        ));

        portInput = Ui.input(this, "Porta");
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setText(String.valueOf(Prefs.getCustomPort(this)));
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        );
        portParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        root.addView(portInput, portParams);

        tlsInput = new CheckBox(this);
        tlsInput.setText("Usa TLS/SSL");
        tlsInput.setTextSize(16);
        tlsInput.setTextColor(0xFF17211D);
        tlsInput.setChecked(Prefs.customUseTls(this));
        LinearLayout.LayoutParams tlsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        );
        tlsParams.setMargins(0, Ui.dp(this, 8), 0, 0);
        root.addView(tlsInput, tlsParams);

        centralInput = new CheckBox(this);
        if (Prefs.hasCentralServer()) {
            centralInput.setText("Oppure utilizza il nostro server centrale\n(i messaggi restano comunque privati e cifrati)");
            centralInput.setTextSize(17);
            centralInput.setTextColor(0xFF0D5C46);
            centralInput.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
            centralInput.setChecked(Prefs.useCentralServer(this));
            LinearLayout.LayoutParams centralParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            centralParams.setMargins(0, Ui.dp(this, 14), 0, 0);
            root.addView(centralInput, centralParams);
            centralInput.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateManualFields();
                }
            });
        } else {
            centralInput.setChecked(false);
            centralInput.setVisibility(View.GONE);
        }

        Button save = Ui.primaryButton(this, "Salva");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        );
        saveParams.setMargins(0, Ui.dp(this, 16), 0, 0);
        root.addView(save, saveParams);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });

        setContentView(root);
        updateManualFields();
    }

    private void saveSettings() {
        String host = hostInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();
        if (!centralInput.isChecked() && host.isEmpty()) {
            hostInput.setError("Host richiesto");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            portInput.setError("Porta non valida");
            return;
        }
        if (port < 1 || port > 65535) {
            portInput.setError("Porta fuori intervallo");
            return;
        }
        if (!Prefs.saveServer(this, host, port, tlsInput.isChecked(), centralInput.isChecked())) {
            Toast.makeText(this, "Salvataggio impostazioni fallito", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Impostazioni salvate", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void updateManualFields() {
        boolean manual = !centralInput.isChecked();
        hostInput.setEnabled(manual);
        portInput.setEnabled(manual);
        tlsInput.setEnabled(manual);
        hostInput.setAlpha(manual ? 1.0f : 0.45f);
        portInput.setAlpha(manual ? 1.0f : 0.45f);
        tlsInput.setAlpha(manual ? 1.0f : 0.45f);
    }
}
