package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.customcontrols.CustomControls;
import net.kdt.pojavlaunch.utils.FileUtils;

import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * An activity dedicated to importing control files.
 */
@SuppressWarnings("IOStreamConstructor")
public class ImportControlActivity extends Activity {

    private Uri mUriData;
    private boolean mHasIntentChanged = true;
    private volatile boolean mIsFileVerified = false;

    private EditText mEditText;

    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(Tools.checkStorageInteractive(this)) {
            Tools.initStorageConstants(getApplicationContext());
        }else {
            // Return early, no initialization needed.
            return;
        }

        setContentView(R.layout.activity_import_control);
        mEditText = findViewById(R.id.editText_import_control_file_name);
    }

    /**
     * Override the previous loaded intent
     * @param intent the intent used to replace the old one.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        if(intent != null) setIntent(intent);
        mHasIntentChanged = true;
    }

    /**
     * Update all over again if the intent changed.
     */
    @Override
    protected void onPostResume() {
        super.onPostResume();
        if(!Tools.checkStorageInteractive(this)) {
            // Don't try to read the file as when this check fails, external storage paths
            // are no longer valid (likely unmounted).
            // checkStorageInteractive() will finish this activity for us.
            return;
        }
        if(!mHasIntentChanged) return;
        mIsFileVerified = false;
        getUriData();
        if(mUriData == null) {
            finishAndRemoveTask();
            return;
        }
        mEditText.setText(trimFileName(Tools.getFileName(this, mUriData)));
        mHasIntentChanged = false;

        //Import and verify thread
        //Kill the app if the file isn't valid.
        new Thread(() -> {
            boolean copied = importControlFile();

            if(copied && verify())mIsFileVerified = true;
            else runOnUiThread(() -> {
                Toast.makeText(
                        ImportControlActivity.this,
                        getText(R.string.import_control_invalid_file),
                        Toast.LENGTH_SHORT).show();
                finishAndRemoveTask();
            });
        }).start();

        //Auto show the keyboard
        Tools.MAIN_HANDLER.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getApplicationContext().getSystemService(INPUT_METHOD_SERVICE);
            imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
            mEditText.setSelection(mEditText.getText().length());
        }, 100);
    }

    /**
     * Start the import.
     * @param view the view which called the function
     */
    public void startImport(View view) {
        String fileName = trimFileName(mEditText.getText().toString());
        //Step 1 check for suffixes.
        if(!isFileNameValid(fileName)){
            Toast.makeText(this, getText(R.string.import_control_invalid_name), Toast.LENGTH_SHORT).show();
            return;
        }
        if(!mIsFileVerified){
            Toast.makeText(this, getText(R.string.import_control_verifying_file), Toast.LENGTH_LONG).show();
            return;
        }

        new File(Tools.CTRLMAP_PATH + "/TMP_IMPORT_FILE.json").renameTo(new File(Tools.CTRLMAP_PATH + "/" + fileName + ".json"));
        Toast.makeText(getApplicationContext(), getText(R.string.import_control_done), Toast.LENGTH_SHORT).show();
        finishAndRemoveTask();
    }

    /**
     * Copy a the file from the Intent data with a provided name into the controlmap folder.
     */
    private boolean importControlFile(){
        File tmp = new File(Tools.CTRLMAP_PATH + "/TMP_IMPORT_FILE.json");
        // Sobra de uma tentativa anterior faria verify() validar o arquivo errado
        if (tmp.exists() && !tmp.delete()) return false;

        File dir = tmp.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return false;

        try (InputStream is = getContentResolver().openInputStream(mUriData);
             OutputStream os = new FileOutputStream(tmp)) {
            if (is == null) return false;
            IOUtils.copy(is, os);
            return true;
        } catch (Exception e) {
            // Exception, não IOException: openInputStream lança SecurityException
            // quando a permissão da Uri não foi concedida (comum em arquivo vindo
            // de outro app) e IllegalArgumentException em Uri malformada. Nenhuma
            // das duas é IOException — sem isso, elas derrubavam o app.
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Tell if the clean version of the filename is valid.
     * @param fileName the string to test
     * @return whether the filename is valid
     */
    private static boolean isFileNameValid(String fileName){
        fileName = trimFileName(fileName);

        if(fileName.isEmpty()) return false;
        return !FileUtils.exists(Tools.CTRLMAP_PATH + "/" + fileName + ".json");
    }

    /**
     * Remove or undesirable chars from the string
     * @param fileName The string to trim
     * @return The trimmed string
     */
    private static String trimFileName(String fileName){
        return fileName
                .replace(".json", "")
                .replaceAll("%..", "/")
                .replace("/", "")
                .replace("\\", "")
                .trim();
    }

    /**
     * Tries to get an Uri from the various sources
     */
    private void getUriData(){
        mUriData = getIntent().getData();
        if(mUriData != null) return;
        try {
            mUriData = getIntent().getClipData().getItemAt(0).getUri();
        }catch (Exception ignored){}
    }

    /**
     * Verify if the control file is valid
     * @return Whether the control file is valid
     */
    private static boolean verify(){
        try{
            String jsonLayoutData = Tools.read(Tools.CTRLMAP_PATH + "/TMP_IMPORT_FILE.json");
            JSONObject layoutJobj = new JSONObject(jsonLayoutData);

            // Layout V1 não tem o campo "version" — é assim que o LayoutConverter
            // o identifica. Exigir "version" aqui rejeitava, como "arquivo
            // inválido", justamente os layouts antigos que o app sabe converter.
            if (!layoutJobj.has("mControlDataList")) return false;
            if (!layoutJobj.has("version")) return true; // V1

            int version = layoutJobj.getInt("version");
            return version >= 1 && version <= CustomControls.CURRENT_VERSION;
        }catch (JSONException | IOException e) {
            e.printStackTrace();
            return false;
        }
    }

}
