package com.example.minichat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class LoginActivity extends Activity {
    private static final CountryOption[] COUNTRIES = new CountryOption[]{
            new CountryOption("Italia", "39"),
            new CountryOption("Svizzera", "41"),
            new CountryOption("Francia", "33"),
            new CountryOption("Germania", "49"),
            new CountryOption("Spagna", "34"),
            new CountryOption("Regno Unito", "44"),
            new CountryOption("Stati Uniti", "1"),
            new CountryOption("Romania", "40"),
            new CountryOption("Albania", "355"),
            new CountryOption("Marocco", "212")
    };

    private Spinner countryInput;
    private EditText phoneInput;
    private EditText passwordInput;
    private EditText otpInput;
    private Button loginButton;
    private Button registerButton;
    private TextView serverView;
    private TextView statusView;
    private boolean waitingForOtp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ChatClient.getInstance().isConnected()) {
            startActivity(new Intent(this, ContactsActivity.class));
            finish();
            return;
        }
        buildUi();
        if (Prefs.hasSession(this)) {
            phoneInput.setText(Prefs.getPhone(this));
            passwordInput.setText(Prefs.getPassword(this));
            login(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServerLabel();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        root.setBackgroundResource(R.color.bg);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = Ui.title(this, "MiniChat");
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton settings = new ImageButton(this);
        settings.setImageResource(android.R.drawable.ic_menu_manage);
        settings.setContentDescription("Impostazioni");
        settings.setBackgroundColor(0x00000000);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(LoginActivity.this, SettingsActivity.class));
            }
        });
        header.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));

        TextView subtitle = Ui.subtitle(this, "Accedi con telefono e password oppure registra un nuovo numero.");
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        root.addView(subtitle, subtitleParams);

        countryInput = new Spinner(this);
        ArrayAdapter<CountryOption> countryAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, COUNTRIES);
        countryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        countryInput.setAdapter(countryAdapter);
        selectCountry(Prefs.getCountryCode(this));
        LinearLayout.LayoutParams countryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        );
        countryParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        root.addView(countryInput, countryParams);

        phoneInput = Ui.input(this, "Numero telefono");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setText(Prefs.getPhone(this));
        LinearLayout.LayoutParams phoneParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        );
        phoneParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        root.addView(phoneInput, phoneParams);

        passwordInput = Ui.input(this, "Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        );
        passwordParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        root.addView(passwordInput, passwordParams);

        otpInput = Ui.input(this, "Codice OTP");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setVisibility(View.GONE);
        LinearLayout.LayoutParams otpParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        );
        otpParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        root.addView(otpInput, otpParams);

        loginButton = Ui.primaryButton(this, "Accedi");
        LinearLayout.LayoutParams loginParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        );
        loginParams.setMargins(0, Ui.dp(this, 14), 0, 0);
        root.addView(loginButton, loginParams);

        registerButton = Ui.primaryButton(this, "Registrati");
        LinearLayout.LayoutParams registerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52)
        );
        registerParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        root.addView(registerButton, registerParams);

        serverView = Ui.subtitle(this, "");
        LinearLayout.LayoutParams serverParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        serverParams.setMargins(0, Ui.dp(this, 18), 0, 0);
        root.addView(serverView, serverParams);

        statusView = Ui.subtitle(this, "");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.setMargins(0, Ui.dp(this, 8), 0, 0);
        root.addView(statusView, statusParams);

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                login(false);
            }
        });
        registerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (waitingForOtp) {
                    confirmRegistration();
                } else {
                    requestOtp();
                }
            }
        });

        setContentView(scrollView);
    }

    private void login(boolean automatic) {
        final String phone = normalizedPhoneFromInput();
        final String password = passwordInput.getText().toString();
        if (!validatePhoneAndPassword(phone, password)) {
            return;
        }

        Prefs.saveCountryCode(this, selectedCountryCode());
        Prefs.savePhone(this, phone);
        setBusy(true, automatic ? "Accesso automatico..." : "Login in corso...");
        ChatClient.getInstance().login(
                Prefs.getHost(this),
                Prefs.getPort(this),
                Prefs.useTls(this),
                phone,
                password,
                authCallback(phone, password, automatic)
        );
    }

    private void requestOtp() {
        final String phone = normalizedPhoneFromInput();
        final String password = passwordInput.getText().toString();
        if (!validatePhoneAndPassword(phone, password)) {
            return;
        }

        Prefs.saveCountryCode(this, selectedCountryCode());
        Prefs.savePhone(this, phone);
        setBusy(true, "Invio OTP...");
        ChatClient.getInstance().requestRegistrationOtp(
                Prefs.getHost(this),
                Prefs.getPort(this),
                Prefs.useTls(this),
                PhoneUtils.withPlus(phone),
                new ChatClient.ActionCallback() {
                    @Override
                    public void onSuccess() {
                        waitingForOtp = true;
                        otpInput.setVisibility(View.VISIBLE);
                        registerButton.setText("Conferma OTP");
                        setBusy(false, "OTP inviato. Inserisci il codice ricevuto.");
                    }

                    @Override
                    public void onError(String message) {
                        setBusy(false, message);
                        Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void confirmRegistration() {
        final String phone = normalizedPhoneFromInput();
        final String password = passwordInput.getText().toString();
        final String otp = otpInput.getText().toString().trim();
        if (!validatePhoneAndPassword(phone, password)) {
            return;
        }
        if (otp.length() < 4) {
            otpInput.setError("Codice non valido");
            return;
        }

        setBusy(true, "Verifica OTP...");
        ChatClient.getInstance().verifyRegistrationOtp(
                Prefs.getHost(this),
                Prefs.getPort(this),
                Prefs.useTls(this),
                PhoneUtils.withPlus(phone),
                password,
                otp,
                authCallback(phone, password, false)
        );
    }

    private boolean validatePhoneAndPassword(String phone, String password) {
        if (PhoneUtils.normalize(phone).length() < 5) {
            phoneInput.setError("Numero non valido");
            return false;
        }
        if (password.length() < 4) {
            passwordInput.setError("Almeno 4 caratteri");
            return false;
        }
        return true;
    }

    private String normalizedPhoneFromInput() {
        return PhoneUtils.normalizeInternational(phoneInput.getText().toString(), selectedCountryCode());
    }

    private String selectedCountryCode() {
        Object selected = countryInput == null ? null : countryInput.getSelectedItem();
        if (selected instanceof CountryOption) {
            return ((CountryOption) selected).code;
        }
        return Prefs.getCountryCode(this);
    }

    private void selectCountry(String countryCode) {
        if (countryInput == null) {
            return;
        }
        String clean = PhoneUtils.countryCodeDigits(countryCode);
        for (int i = 0; i < COUNTRIES.length; i++) {
            if (COUNTRIES[i].code.equals(clean)) {
                countryInput.setSelection(i);
                return;
            }
        }
        countryInput.setSelection(0);
    }

    private ChatClient.ConnectCallback authCallback(final String phone, final String password, final boolean automatic) {
        return new ChatClient.ConnectCallback() {
            @Override
            public void onSuccess() {
                setBusy(false, "");
                if (!Prefs.saveSession(LoginActivity.this, phone, password, ChatClient.getInstance().getMyId())) {
                    Toast.makeText(LoginActivity.this, "Accesso riuscito, sessione non salvata", Toast.LENGTH_LONG).show();
                }
                ChatService.start(LoginActivity.this);
                startActivity(new Intent(LoginActivity.this, ContactsActivity.class));
                finish();
            }

            @Override
            public void onError(String message) {
                if (automatic && Prefs.hasSession(LoginActivity.this)) {
                    ChatClient.getInstance().prepareOffline(LoginActivity.this);
                    startActivity(new Intent(LoginActivity.this, ContactsActivity.class));
                    finish();
                    return;
                }
                setBusy(false, message);
                Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };
    }

    private void setBusy(boolean busy, String status) {
        loginButton.setEnabled(!busy);
        registerButton.setEnabled(!busy);
        statusView.setText(status);
    }

    private void updateServerLabel() {
        if (Prefs.useCentralServer(this)) {
            serverView.setText("Server: centrale TLS");
        } else {
            serverView.setText("Server: " + (Prefs.useTls(this) ? "tls://" : "tcp://") + Prefs.getHost(this) + ":" + Prefs.getPort(this));
        }
    }

    private static final class CountryOption {
        final String name;
        final String code;

        CountryOption(String name, String code) {
            this.name = name;
            this.code = code;
        }

        @Override
        public String toString() {
            return name + " +" + code;
        }
    }
}
