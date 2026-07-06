package com.example.minichat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class ChatClient {
    interface ConnectCallback {
        void onSuccess();
        void onError(String message);
    }

    interface SendCallback {
        void onSent();
        void onError(String message);
    }

    interface ActionCallback {
        void onSuccess();
        void onError(String message);
    }

    interface Listener {
        void onConnectionState(String state);
        void onContactsChanged(List<Contact> contacts);
        void onMessageReceived(ChatMessage message, Contact contact);
        void onError(String message);
    }

    private static final ChatClient INSTANCE = new ChatClient();

    private final Object ioLock = new Object();
    private final Object dataLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final LinkedHashMap<Integer, Contact> contacts = new LinkedHashMap<>();
    private final HashMap<Integer, ArrayList<ChatMessage>> conversations = new HashMap<>();
    private final HashMap<String, String> localContactNames = new HashMap<>();
    private final Object persistLock = new Object();

    private Context appContext;
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private volatile boolean closing;
    private volatile int myId = -1;
    private volatile String myName = "";
    private volatile String myPhone = "";
    private volatile int activeConversationId = -1;
    private boolean persistWorkerRunning;
    private HashMap<Integer, ArrayList<ChatMessage>> pendingPersistSnapshot;

    private ChatClient() {
    }

    static ChatClient getInstance() {
        return INSTANCE;
    }

    void init(Context context) {
        appContext = context.getApplicationContext();
    }

    void prepareOffline(Context context) {
        appContext = context.getApplicationContext();
        String savedPhone = PhoneUtils.normalize(Prefs.getPhone(appContext));
        if (savedPhone.isEmpty()) {
            return;
        }
        myPhone = savedPhone;
        myName = savedPhone;
        myId = Prefs.getUserId(appContext);
        loadConversations();
        if (myId <= 0) {
            myId = inferMyIdFromConversations();
        }
        loadStoredContacts();
        notifyContactsChanged();
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    boolean isConnected() {
        Socket current = socket;
        return current != null && current.isConnected() && !current.isClosed();
    }

    int getMyId() {
        return myId;
    }

    String getMyName() {
        return myName;
    }

    String getMyPhone() {
        return myPhone;
    }

    void setActiveConversationId(int contactId) {
        activeConversationId = contactId;
    }

    List<Contact> getContactsSnapshot() {
        synchronized (dataLock) {
            return new ArrayList<>(contacts.values());
        }
    }

    Contact getContact(int id, String fallbackName) {
        synchronized (dataLock) {
            Contact existing = contacts.get(id);
            return existing != null ? existing : new Contact(id, fallbackName, "", false);
        }
    }

    List<ChatMessage> getConversation(int contactId) {
        synchronized (dataLock) {
            ArrayList<ChatMessage> messages = conversations.get(contactId);
            if (messages == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(messages);
        }
    }

    void login(String host, int port, boolean useTls, String phone, String password, ConnectCallback callback) {
        authenticate(host, port, useTls, "LOGIN", new String[]{PhoneUtils.normalize(phone), password}, callback);
    }

    void requestRegistrationOtp(String host, int port, boolean useTls, String phone, ActionCallback callback) {
        oneShot(host, port, useTls, "REGISTER_BEGIN " + encode(phone), "OTP_SENT", callback);
    }

    void verifyRegistrationOtp(String host, int port, boolean useTls, String phone, String password, String otp, ConnectCallback callback) {
        authenticate(host, port, useTls, "REGISTER_VERIFY", new String[]{phone, password, otp}, callback);
    }

    void register(String host, int port, String name, String phone, String password, ConnectCallback callback) {
        postError(callback, "Registrazione richiede OTP");
    }

    private void oneShot(final String host, final int port, final boolean useTls, final String line, final String expectedPrefix, final ActionCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket tempSocket = null;
                try {
                    tempSocket = connectToServer(host, port, useTls);
                    BufferedReader tempReader = new BufferedReader(new InputStreamReader(tempSocket.getInputStream(), StandardCharsets.UTF_8));
                    BufferedWriter tempWriter = new BufferedWriter(new OutputStreamWriter(tempSocket.getOutputStream(), StandardCharsets.UTF_8));
                    tempWriter.write(line);
                    tempWriter.write("\n");
                    tempWriter.flush();
                    String response = tempReader.readLine();
                    if (response == null) {
                        throw new IOException("Il server ha chiuso la connessione");
                    }
                    if (!response.startsWith(expectedPrefix)) {
                        throw new IOException(parseServerError(response));
                    }
                    postSuccess(callback);
                } catch (Exception e) {
                    postError(callback, e.getMessage() == null ? "Operazione fallita" : e.getMessage());
                } finally {
                    closeQuietly(tempSocket);
                }
            }
        }, "MiniChat-one-shot").start();
    }

    private void authenticate(final String host, final int port, final boolean useTls, final String command, final String[] fields, final ConnectCallback callback) {
        disconnect(false);
        notifyConnectionState("Connessione " + (useTls ? "TLS " : "") + "a " + host + ":" + port);

        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket newSocket = null;
                try {
                    closing = false;
                    newSocket = connectToServer(host, port, useTls);
                    BufferedReader newReader = new BufferedReader(new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
                    BufferedWriter newWriter = new BufferedWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));

                    synchronized (ioLock) {
                        socket = newSocket;
                        reader = newReader;
                        writer = newWriter;
                    }

                    StringBuilder auth = new StringBuilder(command);
                    for (String field : fields) {
                        auth.append(' ').append(encode(field));
                    }
                    writeLine(auth.toString());

                    String response = newReader.readLine();
                    if (response == null) {
                        throw new IOException("Il server ha chiuso la connessione");
                    }
                    if (!response.startsWith("OK ")) {
                        throw new IOException(parseServerError(response));
                    }

                    String[] parts = response.split(" ", 4);
                    if (parts.length < 4) {
                        throw new IOException("Risposta auth non valida");
                    }
                    myId = Integer.parseInt(parts[1]);
                    myName = decode(parts[2]);
                    myPhone = PhoneUtils.normalize(decode(parts[3]));
                    loadConversations();
                    loadStoredContacts();

                    startReaderThread(newSocket, newReader);
                    postSuccess(callback);
                    notifyConnectionState("Connesso come " + myName);
                } catch (final Exception e) {
                    closeQuietly(newSocket);
                    clearConnection();
                    postError(callback, e.getMessage() == null ? "Connessione fallita" : e.getMessage());
                    notifyConnectionState("Disconnesso");
                }
            }
        }, "MiniChat-auth").start();
    }

    private Socket connectToServer(String host, int port, boolean useTls) throws IOException {
        Socket rawSocket = new Socket();
        rawSocket.connect(new InetSocketAddress(host, port), 5000);
        if (!useTls) {
            return rawSocket;
        }

        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tlsSocket = (SSLSocket) factory.createSocket(rawSocket, host, port, true);
            SSLParameters parameters = tlsSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tlsSocket.setSSLParameters(parameters);
            enableModernTls(tlsSocket);
            tlsSocket.startHandshake();
            return tlsSocket;
        } catch (IOException e) {
            closeQuietly(rawSocket);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(rawSocket);
            throw e;
        }
    }

    private void enableModernTls(SSLSocket socket) {
        ArrayList<String> protocols = new ArrayList<>();
        String[] supported = socket.getSupportedProtocols();
        for (String protocol : supported) {
            if ("TLSv1.3".equals(protocol) || "TLSv1.2".equals(protocol)) {
                protocols.add(protocol);
            }
        }
        if (!protocols.isEmpty()) {
            socket.setEnabledProtocols(protocols.toArray(new String[0]));
        }
    }

    void disconnect() {
        disconnect(true);
    }

    private void disconnect(boolean notify) {
        closing = true;
        boolean canKeepOffline = notify && appContext != null && Prefs.hasSession(appContext);
        Socket oldSocket;
        synchronized (ioLock) {
            oldSocket = socket;
            socket = null;
            reader = null;
            writer = null;
        }
        closeQuietly(oldSocket);
        synchronized (dataLock) {
            contacts.clear();
            conversations.clear();
            localContactNames.clear();
        }
        myId = -1;
        myName = "";
        myPhone = "";
        activeConversationId = -1;
        if (canKeepOffline) {
            prepareOffline(appContext);
            notifyConnectionState("Offline");
            return;
        }
        if (notify) {
            notifyConnectionState("Disconnesso");
            notifyContactsChanged();
        }
    }

    void syncContacts(final List<String> phoneNumbers) {
        HashMap<String, String> phoneNames = new HashMap<>();
        for (String rawPhone : phoneNumbers) {
            String phone = PhoneUtils.normalize(rawPhone);
            if (!phone.isEmpty()) {
                phoneNames.put(phone, phone);
            }
        }
        syncContacts(phoneNames);
    }

    void syncContacts(final Map<String, String> phoneNames) {
        if (!isConnected()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    StringBuilder body = new StringBuilder();
                    HashMap<String, String> normalizedNames = new HashMap<>();
                    for (Map.Entry<String, String> entry : phoneNames.entrySet()) {
                        String phone = PhoneUtils.normalize(entry.getKey());
                        if (phone.isEmpty() || phone.equals(myPhone)) {
                            continue;
                        }
                        String displayName = entry.getValue();
                        if (displayName == null || displayName.trim().isEmpty()) {
                            displayName = phone;
                        }
                        normalizedNames.put(phone, displayName.trim());
                        if (body.length() > 0) {
                            body.append(';');
                        }
                        body.append(encode(phone));
                    }
                    synchronized (dataLock) {
                        localContactNames.clear();
                        localContactNames.putAll(normalizedNames);
                    }
                    writeLine("SYNC " + body);
                } catch (IOException e) {
                    notifyError("Sync rubrica fallita: " + e.getMessage());
                }
            }
        }, "MiniChat-sync").start();
    }

    void sendMessage(final int toId, final String text, final SendCallback callback) {
        if (!isConnected()) {
            postError(callback, "Non connesso");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    writeLine("MSG " + toId + " " + encode(text));
                    postSent(callback);
                } catch (final IOException e) {
                    postError(callback, e.getMessage() == null ? "Invio fallito" : e.getMessage());
                }
            }
        }, "MiniChat-send").start();
    }

    private void startReaderThread(final Socket expectedSocket, final BufferedReader expectedReader) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    while ((line = expectedReader.readLine()) != null) {
                        handleLine(line);
                    }
                    if (!closing && socket == expectedSocket) {
                        notifyError("Connessione chiusa dal server");
                    }
                } catch (IOException e) {
                    if (!closing && socket == expectedSocket) {
                        notifyError("Connessione persa: " + e.getMessage());
                    }
                } finally {
                    if (socket == expectedSocket) {
                        disconnect(true);
                    }
                }
            }
        }, "MiniChat-reader").start();
    }

    private void handleLine(String line) {
        if (line.startsWith("CONTACTS")) {
            parseContacts(line);
        } else if (line.startsWith("MSG ")) {
            parseMessage(line);
        } else if (line.startsWith("SENT ")) {
            parseSent(line);
        } else if (line.startsWith("QUEUED ")) {
            parseSent(line);
        } else if (line.startsWith("ERR ")) {
            notifyError(parseServerError(line));
        }
    }

    private void parseContacts(String line) {
        String body = "";
        if (line.length() > "CONTACTS".length()) {
            body = line.substring("CONTACTS".length()).trim();
        }

        LinkedHashMap<Integer, Contact> parsed = new LinkedHashMap<>();
        if (!body.isEmpty()) {
            String[] entries = body.split(";");
            for (String entry : entries) {
                String[] parts = entry.split(":", 4);
                if (parts.length < 4) {
                    continue;
                }
                try {
                    int id = Integer.parseInt(parts[0]);
                    if (id == myId) {
                        continue;
                    }
                    String name = decode(parts[1]);
                    String phone = PhoneUtils.normalize(parts[2]);
                    boolean online = "1".equals(parts[3]);
                    String localName;
                    synchronized (dataLock) {
                        localName = localContactNames.get(phone);
                    }
                    if (localName != null && !localName.trim().isEmpty()) {
                        name = localName.trim();
                    }
                    parsed.put(id, new Contact(id, name, phone, online));
                } catch (Exception ignored) {
                    // Skip malformed contacts and keep the socket alive.
                }
            }
        }

        synchronized (dataLock) {
            contacts.clear();
            contacts.putAll(parsed);
        }
        persistContacts();
        notifyContactsChanged();
    }

    private void parseMessage(String line) {
        String[] parts = line.split(" ", 3);
        if (parts.length < 3) {
            notifyError("Messaggio server non valido");
            return;
        }
        try {
            int fromId = Integer.parseInt(parts[1]);
            String text = decode(parts[2]);
            Contact contact = getContact(fromId, "Utente " + fromId);
            ChatMessage message = new ChatMessage(fromId, myId, text, false, System.currentTimeMillis());
            addMessage(fromId, message);
            persistContacts();
            if (appContext != null && activeConversationId != fromId) {
                NotificationHelper.showMessage(appContext, contact, message);
            }
            notifyMessage(message, contact);
        } catch (Exception e) {
            notifyError("Messaggio server non valido");
        }
    }

    private void parseSent(String line) {
        String[] parts = line.split(" ", 3);
        if (parts.length < 3) {
            notifyError("Conferma invio non valida");
            return;
        }
        try {
            int toId = Integer.parseInt(parts[1]);
            String text = decode(parts[2]);
            Contact contact = getContact(toId, "Utente " + toId);
            ChatMessage message = new ChatMessage(myId, toId, text, true, System.currentTimeMillis());
            addMessage(toId, message);
            persistContacts();
            notifyMessage(message, contact);
        } catch (Exception e) {
            notifyError("Conferma invio non valida");
        }
    }

    private void addMessage(int contactId, ChatMessage message) {
        synchronized (dataLock) {
            ArrayList<ChatMessage> messages = conversations.get(contactId);
            if (messages == null) {
                messages = new ArrayList<>();
                conversations.put(contactId, messages);
            }
            messages.add(message);
        }
        persistConversations();
    }

    private void loadConversations() {
        if (appContext == null) {
            return;
        }
        HashMap<Integer, ArrayList<ChatMessage>> loaded = ConversationStore.load(appContext, myPhone);
        synchronized (dataLock) {
            conversations.clear();
            conversations.putAll(loaded);
        }
    }

    private int inferMyIdFromConversations() {
        synchronized (dataLock) {
            for (ArrayList<ChatMessage> messages : conversations.values()) {
                for (ChatMessage message : messages) {
                    if (message.outgoing && message.fromId > 0) {
                        return message.fromId;
                    }
                    if (!message.outgoing && message.toId > 0) {
                        return message.toId;
                    }
                }
            }
        }
        return -1;
    }

    private void loadStoredContacts() {
        if (appContext == null || myPhone.isEmpty()) {
            return;
        }
        LinkedHashMap<Integer, Contact> stored = ContactStore.load(appContext, myPhone);
        synchronized (dataLock) {
            contacts.clear();
            contacts.putAll(stored);
            for (Integer contactId : conversations.keySet()) {
                if (!contacts.containsKey(contactId)) {
                    contacts.put(contactId, new Contact(contactId, "Utente " + contactId, "", false));
                }
            }
        }
    }

    private void persistContacts() {
        if (appContext == null || myPhone.isEmpty()) {
            return;
        }
        final ArrayList<Contact> snapshot;
        synchronized (dataLock) {
            snapshot = new ArrayList<>(contacts.values());
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                ContactStore.save(appContext, myPhone, snapshot);
            }
        }, "MiniChat-contacts-persist").start();
    }

    private void persistConversations() {
        if (appContext == null || myPhone.isEmpty()) {
            return;
        }
        final String ownerPhone = myPhone;
        final HashMap<Integer, ArrayList<ChatMessage>> snapshot = new HashMap<>();
        synchronized (dataLock) {
            for (Map.Entry<Integer, ArrayList<ChatMessage>> entry : conversations.entrySet()) {
                snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }

        synchronized (persistLock) {
            pendingPersistSnapshot = snapshot;
            if (persistWorkerRunning) {
                return;
            }
            persistWorkerRunning = true;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    HashMap<Integer, ArrayList<ChatMessage>> toSave;
                    synchronized (persistLock) {
                        toSave = pendingPersistSnapshot;
                        pendingPersistSnapshot = null;
                    }
                    if (toSave != null) {
                        ConversationStore.save(appContext, ownerPhone, toSave);
                    }
                    synchronized (persistLock) {
                        if (pendingPersistSnapshot == null) {
                            persistWorkerRunning = false;
                            return;
                        }
                    }
                }
            }
        }, "MiniChat-persist").start();
    }

    private void writeLine(String line) throws IOException {
        synchronized (ioLock) {
            if (writer == null) {
                throw new IOException("Socket non pronta");
            }
            writer.write(line);
            writer.write("\n");
            writer.flush();
        }
    }

    private String parseServerError(String response) {
        if (response != null && response.startsWith("ERR ")) {
            try {
                return decode(response.substring(4));
            } catch (Exception ignored) {
                return "Errore server";
            }
        }
        return response == null ? "Errore server" : response;
    }

    private void clearConnection() {
        synchronized (ioLock) {
            socket = null;
            reader = null;
            writer = null;
        }
        myId = -1;
        myName = "";
        myPhone = "";
    }

    private static void closeQuietly(Socket target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (IOException ignored) {
        }
    }

    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String decode(String value) {
        return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }

    private void notifyConnectionState(final String state) {
        for (final Listener listener : listeners) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onConnectionState(state);
                }
            });
        }
    }

    private void notifyContactsChanged() {
        final List<Contact> snapshot = getContactsSnapshot();
        for (final Listener listener : listeners) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onContactsChanged(snapshot);
                }
            });
        }
    }

    private void notifyMessage(final ChatMessage message, final Contact contact) {
        for (final Listener listener : listeners) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onMessageReceived(message, contact);
                }
            });
        }
    }

    private void notifyError(final String message) {
        for (final Listener listener : listeners) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onError(message);
                }
            });
        }
    }

    private void postSuccess(final ConnectCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess();
            }
        });
    }

    private void postError(final ConnectCallback callback, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }

    private void postSent(final SendCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSent();
            }
        });
    }

    private void postError(final SendCallback callback, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }

    private void postSuccess(final ActionCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess();
            }
        });
    }

    private void postError(final ActionCallback callback, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }
}
