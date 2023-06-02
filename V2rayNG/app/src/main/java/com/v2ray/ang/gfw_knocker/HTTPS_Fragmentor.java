package com.v2ray.ang.gfw_knocker;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.*;


public class HTTPS_Fragmentor extends Thread {

	ServerSocket ss ;
	Socket client_sock;

	String listen_ip;
	int listen_port;
	String target_ip;
	int target_port;
	DoH_over_Fragment DoH_obj;
	boolean isFragment;
	int num_fragment;
	double fragment_sleep; // in second
	boolean is_ready;


	public HTTPS_Fragmentor(String listen_ip1 , int listen_port1 ,
							String target_ip1 , int target_port1 ,
							DoH_over_Fragment DoH_obj1 , boolean isFragment1 ,
							int num_fragment1 , double fragment_sleep1 ){

		listen_ip = listen_ip1;
		listen_port = listen_port1;
		target_ip = target_ip1;
		target_port = target_port1;
		DoH_obj = DoH_obj1;
		isFragment = isFragment1;
		num_fragment = num_fragment1;
		fragment_sleep = fragment_sleep1;
		is_ready = false;
	}


	@Override
	public void run() {
		try{

			ss = new ServerSocket(listen_port,128); // up to 128 concurrent connection queued
			listen_port = ss.getLocalPort();
			System.out.println("HTTPS Listening at "+listen_ip+":"+listen_port);
			is_ready = true;

			while(true){
				System.out.println("waiting for input socket ...");
				client_sock = ss.accept();
				My_upstream_H up_thread = new My_upstream_H(client_sock , target_ip , target_port , DoH_obj , isFragment , num_fragment , fragment_sleep);
				up_thread.start();
			}

		}catch(Exception e){
			System.out.println("Server ERR: "+e.getMessage());
			is_ready = false;
		}finally{
			System.out.println("Server Stopped. Listening Finished.");
			is_ready = false;
		}

		System.out.println("Server Thread Finished");

	}// run



	public String get_listen_ip(){
		return listen_ip;
	}


	public int get_listen_port(){
		// waiting a few millisec to service starts then returning ports
		try{
			for (int i=1;i<100;i++){
				if(is_ready==true){
					break;
				}else{
					System.out.println(i*10 +" milli second waiting to start threaded service");
					Thread.sleep(10); // sleep 10 milisec
				}
			}
		}catch(Exception e){
			System.out.println("err in get listen port: "+e.getMessage());
		}
		return listen_port;
	}


	public void safely_stop_server(){
		try{
			ss.close();
		}catch(Exception e){
			System.out.println("Safe Stop ERR: "+e.getMessage());
			return;
		}
		System.out.println("server socket safely stopped");
	}


}//class Server





class My_upstream_H extends Thread{
	InputStream is;
	OutputStream os;
	Socket client_sock;
	Socket backend_sock;
	String target_ip;
	int target_port;
	DoH_over_Fragment DoH_obj;
	byte[] buff ;
	int b;
	boolean first_flag;
	int num_fragment;
	long fragment_sleep_milisec;
	long first_time_sleep = 100; // wait 100 millisecond for first packet to fully receive


	public My_upstream_H( Socket client_sock1 ,
						String target_ip1 , int target_port1,
						DoH_over_Fragment DoH_obj1 ,  boolean isFragment1 ,
						int num_fragment1 , double fragment_sleep1 ){
		client_sock = client_sock1;
		target_ip = target_ip1;
		target_port = target_port1;
		DoH_obj = DoH_obj1;
		buff = new byte[8192];
		first_flag = isFragment1;
		num_fragment = num_fragment1;
		fragment_sleep_milisec = (long) (fragment_sleep1 * 1000);
	}


	@Override
	public void run() {

		try{
			backend_sock = handle_client_request(client_sock);
			if(backend_sock == null){
				client_sock.close();
				return;
			}

			My_downstream_H down_thread = new My_downstream_H( backend_sock.getInputStream() , client_sock.getOutputStream() );
			down_thread.start();

			is = client_sock.getInputStream();
			os = backend_sock.getOutputStream();

			System.out.println("up-stream started");


			Thread.sleep(first_time_sleep); // wait n millisec for first packet to fully receive
			while(  (b = is.read(buff)) != -1   ){
				if(first_flag==true){
					first_flag=false;
					send_data_in_fragment();
				}else{
					os.write(buff, 0, b);
					os.flush();
				}
			}

		}catch(Exception e){
			System.out.println("up-stream: "+e.getMessage());
		}finally{
			System.out.println("up-stream finished");
		}

		safely_close_socket(client_sock);
		safely_close_socket(backend_sock);

		try{
			os.flush();
			os.close();
			is.close();
		}catch(Exception e){
			System.out.println("up-stream Close ERR: "+e.getMessage());
		}

	}


	public Socket handle_client_request(Socket cli_sock){
		String remote_host;
		int remote_port;
		Socket backend_sock;
		String response_data;
		InputStream cis;
		OutputStreamWriter cosw=null;


		try{
			cis = cli_sock.getInputStream();
			cosw = new OutputStreamWriter( cli_sock.getOutputStream() );

			Thread.sleep(10); //wait 10 milisec to fully recieve packet from client
			b = cis.read(buff);
			String data = new String(buff, 0, b);
			String[] request_lines = data.split("\r\n");
			String[] requestParts = request_lines[0].split(" ");
			String rMethod = requestParts[0];
			String rhost = requestParts[1];

			if(rMethod.equals("CONNECT")){
				if( (target_ip!=null) && (target_port>0) ){  // ignore client request and send traffic to specific ip:port
					remote_host = target_ip;
					remote_port = target_port;
				}else{   // extract actual client request to send traffic to it.
					String[] hp = rhost.split(":");
					remote_host = hp[0];
					remote_port = Integer.parseInt(hp[1]);
				}
			}else if( 	(rMethod.equals("GET")) ||
					(rMethod.equals("POST")) ||
					(rMethod.equals("HEAD")) ||
					(rMethod.equals("OPTIONS")) ||
					(rMethod.equals("PUT")) ||
					(rMethod.equals("DELETE")) ||
					(rMethod.equals("PATCH")) ||
					(rMethod.equals("TRACE")) ) {
				String q_url = rhost.replace("http://","https://");
				System.out.println("redirect to HTTPS (302) "+q_url);
				response_data = "HTTP/1.1 302 Found\r\nLocation: "+q_url+"\r\nProxy-agent: MyProxy/1.0\r\n\r\n";
				cosw.write(response_data);
				cosw.flush();
				cli_sock.close();
				return null;
			}else{
				System.out.println("Unknown method ERR 400 : "+rMethod);
				response_data = "HTTP/1.1 400 Bad Request\r\nProxy-agent: MyProxy/1.0\r\n\r\n";
				cosw.write(response_data);
				cosw.flush();
				cli_sock.close();
				return null;
			}


			if( (DoH_obj!=null) && (target_ip==null)  ){
				if( !isValidIPAddress(remote_host) ){
					System.out.println("query DoH --> "+remote_host);
					remote_host = DoH_obj.query(remote_host);
				}
			}

			System.out.println(remote_host+" --> "+remote_port);

			backend_sock = new Socket(remote_host, remote_port);
			backend_sock.setTcpNoDelay(true);


			response_data = "HTTP/1.1 200 Connection established\r\nProxy-agent: MyProxy/1.0\r\n\r\n";
			cosw.write(response_data);
			cosw.flush();
			return backend_sock;

		}catch(Exception e){
			System.out.println("handle client Req ERR 502: "+e.getMessage());
			response_data = "HTTP/1.1 502 Bad Gateway (is IP filtered?)\r\nProxy-agent: MyProxy/1.0\r\n\r\n";
			try{
				cosw.write(response_data);
				cosw.flush();
				cli_sock.close();
			}catch(Exception e2){
				System.out.println("handle client write 502 ERR: "+e2.getMessage());
			}
			return null;
		}
	}



	public static boolean isValidIPAddress(String ip)
	{
		if (ip == null) {
			return false;
		}
		String zeroTo255 = "(\\d{1,2}|(0|1)\\" + "d{2}|2[0-4]\\d|25[0-5])";
		String regex = zeroTo255 + "\\." + zeroTo255 + "\\." + zeroTo255 + "\\." + zeroTo255;
		Pattern p = Pattern.compile(regex);
		Matcher m = p.matcher(ip);
		return m.matches();
	}


	public void safely_close_socket(Socket sock){
		try{
			if(sock != null) {
				if( (sock.isConnected()) || (!sock.isClosed()) ){
					sock.shutdownInput();
					sock.shutdownOutput();
					sock.close();
				}
			}
		}catch(Exception e){
			System.out.println("socket Close ERR: "+e.getMessage());
		}
	}


	public static List<Integer> pickKRandomInts(int k, int N) {
		if (k > N) {
			k=N-1;
		}
		List<Integer> nums = new ArrayList<>();
		for (int i = 1; i <= N; i++) {
			nums.add(i);
		}
		Collections.shuffle(nums);

		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < k; i++) {
			result.add(nums.get(i));
		}
		Collections.sort(result);
		return result;
	}




	public void send_data_in_fragment(){
		try{
			int L = b;
			List<Integer> indices = pickKRandomInts(num_fragment-1, L);
			int j_pre = 0;
			int j_next;
			for (int i = 0; i < indices.size(); i++) {
				j_next = indices.get(i);
//				System.out.println("HTTPS send from "+ j_pre + " to "+ j_next);
				os.write(buff, j_pre , (j_next - j_pre) );
				os.flush();
				Thread.sleep(fragment_sleep_milisec);
				j_pre = j_next;
			}

			System.out.println("HTTPS send from "+ j_pre + " to "+ L);
			os.write(buff, j_pre, (L-j_pre) );
			os.flush();

		}catch(Exception e){
			System.out.println("err in fragment function: "+e.getMessage());
			return;
		}
	}



} // class upstream







class My_downstream_H extends Thread{
	InputStream is;
	OutputStream os;
	byte[] buff ;
	int b;


	public My_downstream_H(InputStream backend_instream , OutputStream client_outstream){
		is = backend_instream;
		os = client_outstream;
		buff = new byte[4096];
	}

	@Override
	public void run() {

		try{
			System.out.println("down-stream started");

			while(  (b = is.read(buff)) != -1   ){
				os.write(buff, 0, b);
				os.flush();
			}

		}catch(Exception e){
			System.out.println("down-stream: "+e.getMessage());
		}finally{
			System.out.println("down-stream finished");
		}


		try{
			os.flush();
			os.close();
			is.close();
		}catch(Exception e){
			System.out.println("stream Close ERR: "+e.getMessage());
		}

	}

} // class downstream





