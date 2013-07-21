/*
 * University of British Columbia
 * Department of Computer Science
 * CPSC317 - Internet Programming
 * Assignment 2
 * 
 * Author: Jonatan Schroeder
 * January 2013
 * 
 * This code may not be used without written consent of the authors, except for 
 * current and future projects and assignments of the CPSC317 course at UBC.
 */

package ubc.cs317.rtsp.client.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import ubc.cs317.rtsp.client.exception.RTSPException;

import ubc.cs317.rtsp.client.model.Frame;
import ubc.cs317.rtsp.client.model.Session;

/**
 * This class represents a connection with an RTSP server.
 */
public class RTSPConnection {

	private static final int BUFFER_LENGTH = 15000;
	private static final long MINIMUM_DELAY_READ_PACKETS_MS = 20;
	private static final long DELAY = 40;
	
	private BufferedReader breader;
	private RTSPResponse rescode;
	private int sessionID;
	private DatagramSocket dataGS;

	private Socket newSocket;
	private Session session;
	private Timer rtpTimer;
	private Timer playTimer;
	/* Buffers for X frames. Arbitrarily chosen number because it's hard to decide the "right" buffer time.
	 * Ideally, this would be a dynamically changing value but I'm not Watson...yet.
	 */
	int framebuffsize = 100;
	Frame[] framebuff = new Frame[framebuffsize];
	int buffindex = 0;
	
	// Frame count is updated in recieveRTPPacket() and used to calculate the framerate. seqnum[] is used to find out of order frames.	
	int FrameCount = 0;
	int seqnumIndex = 0;
	int[] seqnum = new int[501];
	long timeStart;
	long timePause;
	int NumOutOfOrder = 0;
	
	/*
	 * Gives packet out of order rate.
	 */
	public void outOfOrder() {
		for (int i = 0; i < seqnum.length - 1; i+=2) {
			if (seqnum[i] > seqnum[i+1]) {
				NumOutOfOrder++;
			}
		}	
			System.out.println("Packet out of order rate is " + NumOutOfOrder/20);
	}
	
	/* An attempt to clean up the buffer into a refined buffer.
	 * If the diff between the Cseq of the ith frame in framebuff[] & present frame (pframe)
	 * is too large, then increment i by 10 (or some other arbitrary number) to avoid a completely
	 * out of place frame. Comment out calls to this method to see more out of place frames.
	 */
	public Frame[] bufferCleanup(int i, Frame pframe) {
		
		Frame[] refinedbuff = new Frame[framebuffsize]; // assuming every single frame is valid
		
		// If the frames are half a second or more apart (12 frames), ignore it. Otherwise add it to refined buffer.
		
		if (framebuff[i].getSequenceNumber() - pframe.getSequenceNumber() > 12 && i < framebuffsize) {
			
			i++;	// This is problematic because I go out of bounds for the last frame.
		} else {
			refinedbuff[i] = framebuff[i];
		}
		
		return refinedbuff;
	}
	
	/*
	 * Sorts the current buffer & plays those frames
	 */
	public void bufferReady(Frame pframe) throws RTSPException {
		this.pause(); 
		this.order(framebuff);
		
		for (int i = 0; i < framebuffsize; i++) {
			
			Frame[] refinedbuff = this.bufferCleanup(i, pframe); // comment out this call to see more out of place frames
			
			session.processReceivedFrame(refinedbuff[i]);
			buffindex--;
		}
		
		this.play(); // comment this line out to manually press play again after it pauses & sorts framebuff.
	}
	
	/**
	 * Sorts the frames. Because the frames are "mostly" sorted, I'll use insertion sort to reorder the out of order packets.
	 */
	public void order(Frame[] array) {
		int i, j, newv;
		for (i = 1; i < array.length; i++) {
			newv = array[i].getSequenceNumber();
			j = i;
			while (j > 0 && array[j - 1].getSequenceNumber() > newv) {	// This is a little tricky because I'm comparing
				array[j] = array[j - 1];								// sequence numbers, and replacing frames.
				j--;
			}
			array[j] = array[i];
		}
		
	}
	
	/**
	 * Establishes a new connection with an RTSP server. No message is sent at
	 * this point, and no stream is set up.
	 * 
	 * @param session
	 *            The Session object to be used for connectivity with the UI.
	 * @param server
	 *            The hostname or IP address of the server.
	 * @param port
	 *            The TCP port number where the server is listening to.
	 * @throws RTSPException
	 *             If the connection couldn't be accepted, such as if the host
	 *             name or port number are invalid or there is no connectivity.
	 */
	public RTSPConnection(Session session, String server, int port)
			throws RTSPException {

		this.session = session;
		try {
			newSocket = new Socket(server, port);
		} catch (UnknownHostException e) {
			throw new RTSPException("Unknown host exception in RTSPConnection()");
		} catch (IOException e) {
			throw new RTSPException("IO exception in RTSPConnection()");
		}
		
		InputStreamReader isr;
		try {
			isr = new InputStreamReader(newSocket.getInputStream());
			breader = new BufferedReader(isr);
		} catch (IOException e) {
			System.out.println("io exception while creating RTSP socket reader");
		}
		
		System.out.println("Last line of RTSPConnection()");
	}

	/**
	 * Sends a SETUP request to the server. This method is responsible for
	 * sending the SETUP request, receiving the response and retrieving the
	 * session identification to be used in future messages. It is also
	 * responsible for establishing an RTP datagram socket to be used for data
	 * transmission by the server. The datagram socket should be created with a
	 * random UDP port number, and the port number used in that connection has
	 * to be sent to the RTSP server for setup. This datagram socket should also
	 * be defined to timeout after 1 second if no packet is received.
	 * 
	 * @param videoName
	 *            The name of the video to be setup.
	 * @throws RTSPException
	 *             If there was an error sending or receiving the RTSP data, or
	 *             if the RTP socket could not be created, or if the server did
	 *             not return a successful response.
	 */
	public synchronized void setup(String videoName) throws RTSPException {
		
		 // Creating RTP datagram socket
		 try {
			dataGS = new DatagramSocket(25000);
			dataGS.setSoTimeout(1);
		} catch (SocketException e1) {
			System.out.println("Socket exception while creating datagram socket in setup()");
		}
		 
		 System.out.println("after datagram socket creation");
		 
		 String setup = "SETUP movie.Mjpeg RTSP/1.0\nCSeq: 1\nTransport: RTP/UDP; client_port= 25000\n\n";
		 byte[] setupBytes = setup.getBytes();
		 try {
			newSocket.getOutputStream().write(setupBytes);
		} catch (IOException e) {
			System.out.println("IO Exception in setup() after SETUP request");
		}
		// Flush after setup request
		 try {
			newSocket.getOutputStream().flush();
		} catch (IOException e) {
			System.out.println("IO Exception in setup() after flush() call");
		}
		 
		 System.out.println("after flushing the setup request");
		 
		 //Read RTSP Response
		 
		 try {
		  rescode = RTSPResponse.readRTSPResponse(breader);
		  System.out.println(rescode.getRtspVersion() +" "+ rescode.getResponseCode() +" "+ rescode.getResponseMessage() +" "+ rescode.getCSeq());
		} catch (IOException e) {
			System.out.println("io exception while reading response");
		}
		 //Setting session ID
		 sessionID = rescode.getSessionId();
		 System.out.println("Session id is " + sessionID);
		 
		 
		 System.out.println("last line of setup()");
	}

	/**
	 * Sends a PLAY request to the server. This method is responsible for
	 * sending the request, receiving the response and, in case of a successful
	 * response, starting the RTP timer responsible for receiving RTP packets
	 * with frames.
	 * 
	 * @throws RTSPException
	 *             If there was an error sending or receiving the RTSP data, or
	 *             if the server did not return a successful response.
	 */
	public synchronized void play() throws RTSPException {


		 String play = "PLAY movie.Mjpeg RTSP/1.0\nCSeq: 2\nSession: " + sessionID + "\n\n";
		 byte[] playBytes = play.getBytes();
		 
		 try {
			newSocket.getOutputStream().write(playBytes);
		} catch (IOException e) {
			System.out.println("IO exception while sending play request");
		}
		 
		// Flush after play request
				 try {
					newSocket.getOutputStream().flush();
				} catch (IOException e) {
					System.out.println("IO Exception in play() after flush() call");
				}
				 
				 System.out.println("after flushing the play request");
				 
				//Read RTSP Response
				 
				 try {
				  rescode = RTSPResponse.readRTSPResponse(breader);
				  System.out.println(rescode.getRtspVersion() +" "+ rescode.getResponseCode() +" "+ rescode.getResponseMessage() +" "+ rescode.getCSeq());
				} catch (IOException e) {
					System.out.println("io exception while reading response");
				}
				 
				 if (rescode.getResponseCode() == 200) {
					 this.startRTPTimer();
					 System.out.println("rtp timer started");
					 timeStart = System.currentTimeMillis();
				 }
				 
				 System.out.println("last line of play()");
	}

	/**
	 * Starts a timer that reads RTP packets repeatedly. The timer will wait at
	 * least MINIMUM_DELAY_READ_PACKETS_MS after receiving a packet to read the
	 * next one.
	 */
	private void startRTPTimer() {

		rtpTimer = new Timer();
		
		rtpTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				receiveRTPPacket();
			}
		}, 0, MINIMUM_DELAY_READ_PACKETS_MS);
		
		playTimer = new Timer();
		
		playTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					play();
				} catch (RTSPException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}, 0, DELAY);
	}

	/**
	 * Receives a single RTP packet and processes the corresponding frame. The
	 * data received from the datagram socket is assumed to be no larger than
	 * BUFFER_LENGTH bytes. This data is then parsed into a Frame object (using
	 * the parseRTPPacket method) and the method session.processReceivedFrame is
	 * called with the resulting packet. In case of timeout no exception should
	 * be thrown and no frame should be processed.
	 * 
	 * I realize this method got really bloated trying to do many things, but I
	 * did not have time to refactor.
	 */
	private void receiveRTPPacket() {
		byte[] dpba = new byte[BUFFER_LENGTH];
		DatagramPacket p = new DatagramPacket(dpba, dpba.length);
		try {
			
			dataGS.receive(p);
			Frame pframe = this.parseRTPPacket(p.getData());
			FrameCount++;
			
			
			// Trying to figure out frame loss and out of order packets
			int currCseq = pframe.getSequenceNumber();
			seqnum[seqnumIndex] = currCseq;
			
			
			/*
			 * If the frame buffer is full, sort the frames and play those frames first.
			 */
			if (buffindex == framebuffsize) {
				this.bufferReady(pframe);
			}
			
			
			// If we're dealing with our very first packet, avoid an array out of bounds for the first frame.
			
			if (seqnumIndex != 0) {							
				int prevCseq = seqnum[seqnumIndex - 1];
				
				// Execute this if block if packets are out of order & buffer is not full.
				if (currCseq != (prevCseq + 1) && buffindex != framebuffsize) {
				
					System.out.println(currCseq);	// comment this out if you don't want to print out of order Cseqs.
					
					framebuff[buffindex] = pframe;
					buffindex++;
				}
			}
			seqnumIndex++;
			
			session.processReceivedFrame(pframe);
			
			
		} catch (IOException e) {
			//System.out.println("io exception while recieving rtp packets" + e); //comment it out to avoid clogging the console.
		} catch (RTSPException e) {
			System.out.println("bufferReady()" + e);
		}
		
	}

	/**
	 * Sends a PAUSE request to the server. This method is responsible for
	 * sending the request, receiving the response and, in case of a successful
	 * response, cancelling the RTP timer responsible for receiving RTP packets
	 * with frames.
	 * 
	 * @throws RTSPException
	 *             If there was an error sending or receiving the RTSP data, or
	 *             if the server did not return a successful response.
	 */
	public synchronized void pause() throws RTSPException {

		String pause = "PAUSE movie.Mjpeg RTSP/1.0\nCSeq: 3\nSession: " + sessionID + "\n\n";
		 byte[] pauseBytes = pause.getBytes();
		 
		 try {
			newSocket.getOutputStream().write(pauseBytes);
		} catch (IOException e) {
			System.out.println("IO exception while sending play request");
		}
		 
		 		// Flush after pause request
				 try {
					newSocket.getOutputStream().flush();
				} catch (IOException e) {
					System.out.println("IO Exception in pause() after flush() call");
				}
				 
				 System.out.println("after flushing the pause request");
			
				 //Read RTSP Response
				 
				 try {
				  rescode = RTSPResponse.readRTSPResponse(breader);
				  System.out.println(rescode.getRtspVersion() +" "+ rescode.getResponseCode() +" "+ rescode.getResponseMessage() +" "+ rescode.getCSeq());
				} catch (IOException e) {
					System.out.println("io exception while reading response");
				}
				 
				 if (rescode.getResponseCode() == 200) {
					 rtpTimer.cancel();
				
					// Printing the time stamp, last sequence number, and frame count recorded to calculate the frame rate.
					 System.out.println("rtp timer canceled. Frame count is " + FrameCount + " and sequence number is " + seqnumIndex);
					 timePause = System.currentTimeMillis();
					 System.out.println(timePause - timeStart);
					 
					 /* Printing the sequence numbers here to see if packets were lost. Commented out after collecting stats.
					 for (int i = 0; i <= seqnumIndex; i++) {
						 System.out.println(seqnum[i]);
					 } */
				}
				 
				 System.out.println("last line of pause()");		 
	}

	/**
	 * Sends a TEARDOWN request to the server. This method is responsible for
	 * sending the request, receiving the response and, in case of a successful
	 * response, closing the RTP socket. This method does not close the RTSP
	 * connection, and a further SETUP in the same connection should be
	 * accepted. Also this method can be called both for a paused and for a
	 * playing stream, so the timer responsible for receiving RTP packets will
	 * also be cancelled.
	 * 
	 * @throws RTSPException
	 *             If there was an error sending or receiving the RTSP data, or
	 *             if the server did not return a successful response.
	 */
	public synchronized void teardown() throws RTSPException {

		String tear = "TEARDOWN movie.Mjpeg RTSP/1.0\nCSeq: 3\nSession: " + sessionID + "\n\n";
		 byte[] tearBytes = tear.getBytes();
		 
		 try {
			newSocket.getOutputStream().write(tearBytes);
		} catch (IOException e) {
			System.out.println("IO exception while sending teardown request");
		}
		 
		 		// Flush after pause request
				 try {
					newSocket.getOutputStream().flush();
				} catch (IOException e) {
					System.out.println("IO Exception in teardown() after flush() call");
				}
				 
				 System.out.println("after flushing the teardown request");
				 
				 //Read RTSP Response
				 
				 try {
				  rescode = RTSPResponse.readRTSPResponse(breader);
				  System.out.println(rescode.getRtspVersion() +" "+ rescode.getResponseCode() +" "+ rescode.getResponseMessage());
				  System.out.println(rescode.getCSeq());
				} catch (IOException e) {
					System.out.println("io exception while reading response");
				}
				 
				 if (rescode.getResponseCode() == 200) {
					 dataGS.close();
					 rtpTimer.cancel();
					 System.currentTimeMillis();
					 System.out.println("rtp socket closed and timer canceled and the frame count is " + FrameCount);
					 this.outOfOrder();
				}
				 
				 System.out.println("last line of teardown()");
	}

	/**
	 * Closes the connection with the RTSP server. This method should also close
	 * any open resource associated to this connection, such as the RTP
	 * connection, if it is still open.
	 */
	public synchronized void closeConnection() {
		if (!dataGS.isClosed()) {
			dataGS.close();
			System.out.println("You disconnected while playing the movie");
		} 
		
		try {
			newSocket.close();
		} catch (IOException e) {
			System.out.println("io exception while closing RTSP socket");
		}
	}

	/**
	 * Parses an RTP packet into a Frame object.
	 * 
	 * @param packet
	 *            the byte representation of a frame, corresponding to the RTP
	 *            packet.
	 * @return A Frame object.
	 */
	private static Frame parseRTPPacket(byte[] packet) {
		byte payloadType = (byte) (packet[1] & 0x7f);
		boolean marker;
		
		if (((packet[1] & 0x80) >> 7) == 0) {
			marker = false;
		} else {
			marker = true;
		}
		
		short sequenceNumber = (short) (packet[3] | packet[2] << 8);
		int timestamp = (packet[4] << 24 | packet[5] << 16 | packet[6] << 8 | packet[7]);
		
		byte[] payload = packet;
		int length = packet.length - 12;
		
		Frame pframe = new Frame(payloadType, marker, sequenceNumber, timestamp, payload, 12, length);
		return pframe; // Replace with a proper Frame
	}
}
