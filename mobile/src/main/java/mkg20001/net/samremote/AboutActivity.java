package mkg20001.net.samremote;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.View;

import mkg20001.net.samremotecommon.Tools;

public class AboutActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        FloatingActionButton fab=(FloatingActionButton) findViewById(R.id.stateFAB);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(AboutActivity.this, Remote.class);
                startActivity(intent);
            }
        });

        Drawable draw = ContextCompat.getDrawable(AboutActivity.this,R.drawable.close);
        draw.setColorFilter(Tools.filter());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fab.setForeground(draw);
        } else {
            fab.setImageDrawable(draw);
        }
    }
}
