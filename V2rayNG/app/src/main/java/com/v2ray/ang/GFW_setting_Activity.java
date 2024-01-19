package com.v2ray.ang;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Adapter;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.v2ray.ang.gfwknocker.my_preference_storage;

import java.util.Arrays;

public class GFW_setting_Activity extends AppCompatActivity {

    Switch sw_remote;
    Switch sw_frag;
    Spinner isp_spinner;
    EditText nfrag;
    EditText sleepfrag;
    EditText cfip;
    Spinner cfip_list;
    CheckBox ip_checkbox;
    CheckBox cdn_checkbox;
    Button btn_save;
    my_preference_storage mystrg = new my_preference_storage();

    CheckBox config_checkbox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gfw_setting);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar!=null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }


        sw_remote = (Switch) findViewById(R.id.remote_update);
        sw_frag = (Switch) findViewById(R.id.use_fragment);
        isp_spinner = (Spinner) findViewById(R.id.spinner2);
        nfrag = (EditText) findViewById(R.id.num_frag);
        sleepfrag = (EditText) findViewById(R.id.fragment_sleep);
        cfip = (EditText) findViewById(R.id.CF_IP);
        cfip_list = (Spinner) findViewById(R.id.spinner_cf_ip);
        cdn_checkbox = (CheckBox) findViewById(R.id.use_cdn_checkbox);
        ip_checkbox = (CheckBox) findViewById(R.id.manual_ip_checkbox);
        btn_save = (Button) findViewById(R.id.button_save);
        config_checkbox = (CheckBox) findViewById(R.id.config_ip_checkbox);


        load_switch_state_from_storage();
        fix_switch_dependency();

        sw_frag.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                fix_switch_dependency();
            }
        });


        sw_remote.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                fix_switch_dependency();
            }
        });


        ip_checkbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    cdn_checkbox.setChecked(false);
                    config_checkbox.setChecked(false);
                }
                fix_switch_dependency();
            }
        });

        cdn_checkbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    ip_checkbox.setChecked(false);
                    config_checkbox.setChecked(false);
                }
                fix_switch_dependency();
            }
        });

        config_checkbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    ip_checkbox.setChecked(false);
                    cdn_checkbox.setChecked(false);
                }
                fix_switch_dependency();
            }
        });


        btn_save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean use_remote_update = sw_remote.isChecked();
                boolean use_fragment = sw_frag.isChecked();
                String isp = isp_spinner.getSelectedItem().toString();
                int isp_item_index = isp_spinner.getSelectedItemPosition();
                int num_fragment = Integer.parseInt(nfrag.getText().toString());
                double fragment_sleep = Double.parseDouble(sleepfrag.getText().toString());
                String manual_ip = cfip.getText().toString();
                boolean is_manual_ip = ip_checkbox.isChecked();
                boolean is_cdn_ip = cdn_checkbox.isChecked();
                boolean is_config_chbox = config_checkbox.isChecked();
                int cflist_item_index = cfip_list.getSelectedItemPosition();
                int n_item = cfip_list.getCount();
                String[] cdn_ip_list = new String[n_item];
                for(int i=0;i<n_item;i++){
                    cdn_ip_list[i] = cfip_list.getItemAtPosition(i).toString();
                }
                String cdn_ip = cdn_ip_list[cflist_item_index];



                mystrg.put_value("sw_remote",String.valueOf(use_remote_update) );
                mystrg.put_value("sw_frag",String.valueOf(use_fragment) );
                mystrg.put_value("isp", isp );
                mystrg.put_value("isp_item_index", String.valueOf(isp_item_index) );
                mystrg.put_value("cflist_item_index", String.valueOf(cflist_item_index) );
                mystrg.put_value("nfrag",String.valueOf(num_fragment) );
                mystrg.put_value("sleepfrag",String.valueOf(fragment_sleep) );
                mystrg.put_value("manual_ip",manual_ip );
                mystrg.put_value("cdn_ip",cdn_ip );
                mystrg.put_value("ip_checkbox",String.valueOf(is_manual_ip) );
                mystrg.put_value("cdn_checkbox",String.valueOf(is_cdn_ip));
                mystrg.put_value("config_ip_checkbox",String.valueOf(is_config_chbox));
                mystrg.put_array("cfip_list",cdn_ip_list);

                Toast.makeText(GFW_setting_Activity.this, "settings saved", Toast.LENGTH_SHORT).show();

            }
        });



    }



    public void fix_switch_dependency(){

        // switch dependency
//        if( !sw_frag.isChecked() ){
//            sw_remote.setEnabled(false);
//            isp_spinner.setEnabled(false);
//            nfrag.setEnabled(false);
//            sleepfrag.setEnabled(false);
//            cfip.setEnabled(false);
//            cfip_list.setEnabled(false);
//            cdn_checkbox.setEnabled(false);
//            ip_checkbox.setEnabled(false);
//        }else if( sw_remote.isChecked() ){
//            sw_remote.setEnabled(true);
//            isp_spinner.setEnabled(true);
//            nfrag.setEnabled(false);
//            sleepfrag.setEnabled(false);
//            cfip.setEnabled(false);
//            cfip_list.setEnabled(false);
//            cdn_checkbox.setEnabled(false);
//            cdn_checkbox.setChecked(true);
//            ip_checkbox.setEnabled(false);
//            ip_checkbox.setChecked(false);

//        }else if(ip_checkbox.isChecked()){
        if(ip_checkbox.isChecked()){
            sw_remote.setEnabled(true);
            isp_spinner.setEnabled(true);
            nfrag.setEnabled(true);
            sleepfrag.setEnabled(true);
            cfip.setEnabled(true);
            cfip_list.setEnabled(false);
            cdn_checkbox.setEnabled(true);
            cdn_checkbox.setChecked(false);
            ip_checkbox.setEnabled(true);
            config_checkbox.setChecked(false);
        }else if(cdn_checkbox.isChecked()) {
            sw_remote.setEnabled(true);
            isp_spinner.setEnabled(true);
            nfrag.setEnabled(true);
            sleepfrag.setEnabled(true);
            cfip.setEnabled(false);
            cfip_list.setEnabled(true);
            cdn_checkbox.setEnabled(true);
            ip_checkbox.setChecked(false);
            ip_checkbox.setEnabled(true);
            config_checkbox.setChecked(false);
        }else if(config_checkbox.isChecked()){
            sw_remote.setEnabled(true);
            isp_spinner.setEnabled(true);
            nfrag.setEnabled(true);
            sleepfrag.setEnabled(true);
            cfip.setEnabled(false);
            cfip_list.setEnabled(false);
            cdn_checkbox.setEnabled(true);
            cdn_checkbox.setChecked(false);
            ip_checkbox.setChecked(false);
            ip_checkbox.setEnabled(true);
        }else{
            sw_remote.setEnabled(true);
            isp_spinner.setEnabled(true);
            nfrag.setEnabled(true);
            sleepfrag.setEnabled(true);
            cfip.setEnabled(false);
            cfip_list.setEnabled(false);
            cdn_checkbox.setEnabled(true);
            ip_checkbox.setEnabled(true);
        }

    }



    public void load_switch_state_from_storage(){

        if(mystrg.get_value("sw_frag","").equals("true")){
            sw_frag.setChecked(true);
        }else{
            sw_frag.setChecked(false);
        }

        if(mystrg.get_value("sw_remote","").equals("true")){
            sw_remote.setChecked(true);
        }else{
            sw_remote.setChecked(false);
        }

        if(mystrg.get_value("ip_checkbox" , "").equals("true")) {
            ip_checkbox.setChecked(true);
        }else{
            ip_checkbox.setChecked(false);
        }

        if(mystrg.get_value("cdn_checkbox" , "").equals("true")) {
            cdn_checkbox.setChecked(true);
        }else{
            cdn_checkbox.setChecked(false);
        }

        if(mystrg.get_value("config_ip_checkbox" , "").equals("true")) {
            config_checkbox.setChecked(true);
        }else{
            config_checkbox.setChecked(false);
        }


        String[] myiplist = mystrg.get_array("cfip_list");
        if(myiplist.length>0) {
//            System.out.println("cfip_list in mystrg ->"+Arrays.toString(myiplist));

            ArrayAdapter<String> adpt = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_dropdown_item,
                    myiplist);
            adpt.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            cfip_list.setAdapter(adpt);
        }

        int cflist_id = Integer.parseInt(mystrg.get_value("cflist_item_index","0"));
        cfip_list.setSelection(cflist_id);
        int isp_id = Integer.parseInt(mystrg.get_value("isp_item_index","0"));
        isp_spinner.setSelection(isp_id);
        nfrag.setText(mystrg.get_value("nfrag","90"));
        sleepfrag.setText(mystrg.get_value("sleepfrag","0.007"));
        cfip.setText(mystrg.get_value("manual_ip","discord.com"));

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