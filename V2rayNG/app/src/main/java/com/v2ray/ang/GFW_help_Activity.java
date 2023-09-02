package com.v2ray.ang;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.v2ray.ang.gfwknocker.my_preference_storage;
import com.v2ray.ang.gfwknocker.wget_module;

import android.os.Bundle;
import android.view.MenuItem;
import android.webkit.WebView;
import android.widget.TextView;

public class GFW_help_Activity extends AppCompatActivity {
    my_preference_storage mystrg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gfw_help);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar!=null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mystrg = new my_preference_storage(this);
        String url_announcement_txt = mystrg.get_value("url_announcement_txt",getResources().getString(R.string.announcement_txt_url));
        String url_announcement_html = mystrg.get_value("url_announcement_html",getResources().getString(R.string.announcement_html_url));

        TextView tv = (TextView) findViewById(R.id.textView_help_remote);
        WebView wv = (WebView) findViewById(R.id.webview_help_remote);


        // Start a new thread to fetch the URL
        new Thread(new Runnable() {
            @Override
            public void run() {
                String s7 = wget_module.auto_routed_fetch_remote_text(url_announcement_txt);
                final String mytxt;
                if((s7==null) || (s7.isEmpty())) {
                    mytxt = "fail to fetch remote txt";
                }else{
                    mytxt = s7;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tv.setText(mytxt);
                    }
                });
            }
        }).start();



        new Thread(new Runnable() {
            @Override
            public void run() {
                String s8 = wget_module.auto_routed_fetch_remote_text(url_announcement_html);
                final String myhtml;
                if((s8==null) || (s8.isEmpty())) {
                    myhtml = "fail to fetch webpage";
                }else{
                    myhtml = s8;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        wv.loadData(myhtml,"text/html","utf-8");
                    }
                });
            }
        }).start();


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

