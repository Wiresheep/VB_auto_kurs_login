package de.wiresheep.kursplaner;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Device-local credentials. The key intentionally requires no biometric/unlock per operation. */
final class WebAccount {
    private static final String ALIAS="kursfenster_web_account";
    private final SharedPreferences prefs;
    WebAccount(Context context) { prefs=context.getSharedPreferences("web_account",0); }
    boolean configured() { return prefs.contains("credentials"); }
    String revision() { return prefs.getString("credentials",""); }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias(ALIAS)) return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build());
        return generator.generateKey();
    }
    void save(String email,String password) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(new JSONObject().put("email",email).put("password",password).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String value=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP);
        if(!prefs.edit().putString("credentials",value).commit()) throw new java.io.IOException("Speichern fehlgeschlagen.");
    }
    String[] read() throws Exception {
        String[] parts=revision().split(":",-1);
        if(parts.length!=2) throw new java.io.IOException("Webzugang fehlt.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        JSONObject data=new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
        return new String[]{data.getString("email"),data.getString("password")};
    }
    void clear() {
        if(!prefs.edit().clear().commit()) throw new IllegalStateException("Webzugang konnte nicht gelöscht werden.");
    }
}
