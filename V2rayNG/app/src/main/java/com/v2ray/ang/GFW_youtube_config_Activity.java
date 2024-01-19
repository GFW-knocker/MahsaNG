package com.v2ray.ang.gfwknocker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.v2ray.ang.R;
import com.v2ray.ang.ui.MainActivity;
import com.v2ray.ang.util.AngConfigManager;
import com.v2ray.ang.util.MmkvManager;

public class GFW_youtube_config_Activity extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gfw_youtube_config);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar!=null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
//
//        String url_ADS_txt = mystrg.get_value("url_ADS_txt",getResources().getString(R.string.announcement_txt_url));
//        String url_ADS_html = mystrg.get_value("url_ADS_html",getResources().getString(R.string.announcement_html_url));


        Button btn = (Button) findViewById(R.id.btn_youtube_config);

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                String server = "vmess://ew0KICAidiI6ICIyIiwNCiAgInBzIjogIllvdXR1YmUgJiBUd2l0dGVyIiwNCiAgImFkZCI6ICJnZncueW91dHViZS5jb20iLA0KICAicG9ydCI6ICI0NDMiLA0KICAiaWQiOiAiZTA4NTUxMTktNGIxOC00MDQ1LWI5MDMtOGY2OTliMGM1NDE0IiwNCiAgImFpZCI6ICIwIiwNCiAgInNjeSI6ICJhdXRvIiwNCiAgIm5ldCI6ICJ0Y3AiLA0KICAidHlwZSI6ICJub25lIiwNCiAgImhvc3QiOiAiIiwNCiAgInBhdGgiOiAiIiwNCiAgInRscyI6ICIiLA0KICAic25pIjogIiIsDQogICJhbHBuIjogIiIsDQogICJmcCI6ICIiDQp9";
                String mahsa_subid = MmkvManager.INSTANCE.import_Mahsa_Subscription("GFW-knocker");
                AngConfigManager.INSTANCE.importBatchConfig(server, mahsa_subid , false);

                Toast.makeText(GFW_youtube_config_Activity.this,"enjoy direct Youtube & Twitter",Toast.LENGTH_LONG).show();
            }
        });


    }


    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item){
        if (item.getItemId() == android.R.id.home) {
            this.finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


}

