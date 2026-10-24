package io.github.shivamsingh262.photoprints;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

/**
 * The app's tile and its module card on cleanyourtv's Home: the same scene as the screensaver, right away, until
 * Back. The screen stays on while it shows.
 */
public class SlideshowActivity extends Activity {

    private Prints prints;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.prints);
        prints = new Prints(this, getWindow().getDecorView());
        if (OnlinePhotos.apiKey(this).isEmpty()) showApiKeyDialog();
    }

    @Override
    protected void onStart() {
        super.onStart();
        prints.start();
    }

    @Override
    protected void onStop() {
        prints.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        prints.release();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showApiKeyDialog();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showApiKeyDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Pixabay API key");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String current = OnlinePhotos.apiKey(this);
        if (!current.isEmpty()) input.setText(current);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Connect Pixabay")
                .setMessage("Add your free API key from pixabay.com/api/docs. The key stays on this TV. Press MENU to change it later.")
                .setView(input)
                .setPositiveButton("Save", null)
                .setNegativeButton("Later", (d, which) -> d.dismiss())
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key = input.getText().toString().trim();
            if (key.isEmpty()) {
                input.setError("Enter your Pixabay API key");
                return;
            }
            OnlinePhotos.setApiKey(this, key);
            dialog.dismiss();
            Toast.makeText(this, "Pixabay key saved. Refreshing images.", Toast.LENGTH_SHORT).show();
            if (prints != null) prints.refreshNow();
        }));
        dialog.show();
    }
}
