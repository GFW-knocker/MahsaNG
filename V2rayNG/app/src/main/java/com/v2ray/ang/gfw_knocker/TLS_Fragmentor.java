package com.v2ray.ang.gfw_knocker;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;


public class TLS_Fragmentor extends Thread {
	
	ServerSocket ss ;
	Socket client_sock;
	
	String listen_ip;
	int listen_port;
	int target_port;
	String target_ip;
	boolean isFragment;
	int num_fragment;
	double fragment_sleep; // in second
	boolean is_ready;

	
	public TLS_Fragmentor(String listen_ip1 , int listen_port1 , String target_ip1 , int target_port1 , boolean isFragment1 , int num_fragment1 , double fragment_sleep1){
		listen_ip = listen_ip1;
		listen_port = listen_port1;
		target_ip = target_ip1;
		target_port = target_port1;
		isFragment = isFragment1;
		num_fragment = num_fragment1;
		fragment_sleep = fragment_sleep1;
		is_ready = false;
	}
	
	
	@Override
	public void run() {
		try{
			
			ss = new ServerSocket(listen_port);
			listen_port = ss.getLocalPort();			
			System.out.println("now Listening at "+listen_ip+":"+listen_port);
			is_ready = true;

			while(true){					
					System.out.println("waiting for input socket ...");					
					client_sock = ss.accept();						
					My_upstream up_thread = new My_upstream(client_sock , target_ip , target_port , isFragment , num_fragment , fragment_sleep);						
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





class My_upstream extends Thread{
	String target_ip;
	int target_port;
	InputStream is;
	OutputStream os;
	Socket client_sock;
	Socket backend_sock;
	byte[] buff ;
	int b;
	boolean first_flag;
	int num_fragment;
	long fragment_sleep_milisec;

	
	
	public My_upstream( Socket client_sock1 , String target_ip1 , int target_port1 , boolean isFragment1 , int num_fragment1 , double fragment_sleep1){
		target_ip = target_ip1;
		target_port = target_port1;
		client_sock = client_sock1;
		buff = new byte[4096];
		first_flag = isFragment1;
		num_fragment = num_fragment1;
		fragment_sleep_milisec = (long) (fragment_sleep1 * 1000);
	}
	

	@Override
	public void run() {
				
		try{
			backend_sock = new Socket(target_ip, target_port);
			backend_sock.setTcpNoDelay(true);

			My_downstream down_thread = new My_downstream( backend_sock.getInputStream() , client_sock.getOutputStream() );						
			down_thread.start();

			is = client_sock.getInputStream();
			os = backend_sock.getOutputStream();

			System.out.println("up-stream started");

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
				// System.out.println("send from "+ j_pre + " to "+ j_next);				
				os.write(buff, j_pre , (j_next - j_pre) );
				os.flush();
				Thread.sleep(fragment_sleep_milisec);
				j_pre = j_next;				
			}

			// System.out.println("send from "+ j_pre + " to "+ L);
			os.write(buff, j_pre, (L-j_pre) );
			os.flush();
			
		}catch(Exception e){
			System.out.println("err in fragment function: "+e.getMessage());
			return;
		}
	}



} // class upstream







class My_downstream extends Thread{
	InputStream is;
	OutputStream os;
	byte[] buff ;
	int b;

	
	public My_downstream(InputStream backend_instream , OutputStream client_outstream){
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



