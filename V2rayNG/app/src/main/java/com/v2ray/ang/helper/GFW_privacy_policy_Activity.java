package com.v2ray.ang.helper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import com.v2ray.ang.R;

import java.io.InputStreamReader;
import java.io.Reader;

public class GFW_privacy_policy_Activity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gfw_privacy_policy);


        ActionBar actionBar = getSupportActionBar();
        if(actionBar!=null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }


        TextView tv = (TextView) findViewById(R.id.textView_privacy_policy);

        String tmp = fetch_text_file_from_asset(GFW_privacy_policy_Activity.this);
        tv.setText(tmp);


    }





    // read text file from asset
    private String fetch_text_file_from_asset(Context ctx){
        try {

            int bufferSize = 4096;
            char[] buffer = new char[bufferSize];
            StringBuilder sb = new StringBuilder();
            Reader in = new InputStreamReader(ctx.getAssets().open("privacy_policy.txt"));
            for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0; ) {
                sb.append(buffer, 0, numRead);
            }
            in.close();
            return sb.toString();

        }catch(Exception e){
            System.out.println("file reading ERR: "+e.getMessage());
            return "";
        }
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