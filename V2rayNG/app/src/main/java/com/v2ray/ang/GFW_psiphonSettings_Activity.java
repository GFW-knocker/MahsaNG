package com.v2ray.ang.gfwknocker;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.v2ray.ang.AppConfig;
import com.v2ray.ang.R;
import com.v2ray.ang.ui.BaseActivity;
import com.v2ray.ang.util.Utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GFW_psiphonSettings_Activity extends BaseActivity {

    my_preference_storage mystrg = new my_preference_storage();

    Button btn_save;
    EditText edt_Psiphonsocks;
    EditText edt_Psiphonhttp;
    Spinner sp1;
    Spinner sp_proto;
    Spinner sp_aggr;
    TextView tv_region;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_gfw_psiphon_settings);

        setTitle("Internal Pisphon setting");

//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
//            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
//            return insets;
//        });


        btn_save = (Button) findViewById(R.id.button_psiphon_save1);


        edt_Psiphonsocks = (EditText) findViewById(R.id.edt_psiphon_socks_port1);
        edt_Psiphonhttp = (EditText) findViewById(R.id.edt_psiphon_http_port1);
        tv_region = (TextView) findViewById(R.id.tv_psiphon_region);
        sp1 = (Spinner) findViewById(R.id.psiphon_country_spinner2);
        sp_proto = (Spinner) findViewById(R.id.psiphon_protocol_spinner1);
        sp_aggr = (Spinner) findViewById(R.id.psiphon_aggressive_spinner1);

        load_edt_text_from_storage();


        sp1.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = parent.getItemAtPosition(position).toString();
                String country_flag = GFW_client_ip.countryCodeToFlag(selected);
                String country_name = GFW_client_ip.countryCodeToName(selected);
                if(country_name.isEmpty()){
                    country_name = selected;
                }
                tv_region.setText(country_flag+"("+country_name+")");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });


        btn_save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                String socks_port = edt_Psiphonsocks.getText().toString();
                String http_port = edt_Psiphonhttp.getText().toString();

                String country1 = sp1.getSelectedItem().toString();

                String proto1 = sp_proto.getSelectedItem().toString();

                String aggr1 = sp_aggr.getSelectedItem().toString();

                int lsocks = socks_port.length();
                int lhttp = http_port.length();

                int n_socks = 0;
                int n_http = 0;

                if (country1.equalsIgnoreCase("Auto")) {
                    country1 = "";
                }

                if (lsocks > 0) {
                    try {
                        n_socks = Integer.parseInt(socks_port);
                    } catch (java.lang.NumberFormatException e) {
                        n_socks = 0;
                    }
                }

                if (lhttp > 0) {
                    try {
                        n_http = Integer.parseInt(http_port);
                    } catch (java.lang.NumberFormatException e) {
                        n_http = 0;
                    }
                }


                if ((n_socks <= 0) || (n_socks >= 65535)) {
                    Toast.makeText(GFW_psiphonSettings_Activity.this, "socks port must between 0 to 65534", Toast.LENGTH_LONG).show();
                } else if ((n_http <= 0) || (n_http >= 65535)) {
                    Toast.makeText(GFW_psiphonSettings_Activity.this, "http port must between 0 to 65534", Toast.LENGTH_LONG).show();
                } else if ((n_http == n_socks)) {
                    Toast.makeText(GFW_psiphonSettings_Activity.this, "http & socks port must not equal", Toast.LENGTH_LONG).show();
                } else if ((n_http == 10808) || (n_http==10809) || (n_socks==10808) || (n_socks==10809) ) {
                    Toast.makeText(GFW_psiphonSettings_Activity.this, "port 10808 & 10809 reserved for v2ray and must not be used", Toast.LENGTH_LONG).show();
                } else {
                    try {
                        mystrg.put_value("psiphon_socks_port", socks_port);
                        mystrg.put_value("psiphon_http_port", http_port);
                        mystrg.put_value("psiphon_country_code", country1);
                        mystrg.put_value("psiphon_protocol", proto1);
                        mystrg.put_value("psiphon_aggressive", aggr1);

                        Toast.makeText(GFW_psiphonSettings_Activity.this, "settings saved for psiphon", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(GFW_psiphonSettings_Activity.this, "ERR in saving psiphon setting", Toast.LENGTH_LONG).show();
                    }

                }
            }
        });

    }




    public void load_edt_text_from_storage(){
        edt_Psiphonsocks.setText(mystrg.get_value("psiphon_socks_port",AppConfig.PSIPHON_SOCKS));
        edt_Psiphonhttp.setText(mystrg.get_value("psiphon_http_port",AppConfig.PSIPHON_HTTP));

        String tmp = mystrg.get_value("psiphon_country_code","Auto");

        ArrayAdapter<String> adapter = (ArrayAdapter<String>) sp1.getAdapter();
        int position = adapter.getPosition(tmp);
        if (position >= 0) {
            sp1.setSelection(position);
        }

        String country_flag = GFW_client_ip.countryCodeToFlag(tmp);
        String country_name = GFW_client_ip.countryCodeToName(tmp);
        if(country_name.isEmpty()){
            country_name = tmp;
        }
        tv_region.setText(country_flag+"("+country_name+")");


        String proto2 = mystrg.get_value("psiphon_protocol","auto");
        ArrayAdapter<String> adapter2 = (ArrayAdapter<String>) sp_proto.getAdapter();
        int pos2 = adapter2.getPosition(proto2);
        if (pos2 >= 0) {
            sp_proto.setSelection(pos2);
        }


        String aggr3 = mystrg.get_value("psiphon_aggressive","OFF");
        ArrayAdapter<String> adapter3 = (ArrayAdapter<String>) sp_aggr.getAdapter();
        int pos3 = adapter3.getPosition(aggr3);
        if (pos3 >= 0) {
            sp_aggr.setSelection(pos3);
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