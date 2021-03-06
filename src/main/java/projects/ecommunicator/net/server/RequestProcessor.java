package projects.ecommunicator.net.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Hashtable;
import java.util.Vector;

import projects.ecommunicator.utility.ScoolConstants;
import projects.ecommunicator.utility.Utility;

/** 
 * This Thread is spawned by ECommunicatorServer Thread. For each new socket connection
 * Server spawns this Thread. This Thread is responsible for reading data from a client
 * and sending to all the clients in the same session. Following application protocol is
 * used to receive and send data. This protocol is built upon TCP/IP protocol.
 *
 *                            Header                                                      
 *                              |
 *                              V
 * |----------------------------------------------------------------
 * |                                                                |
 * SessionId |TrackingId |UserId |Role |PayloadLength |Dummy1 |Dummy2
 *     8          16         8      2        8            8        8
 *
 *                     Payload 
 *                       |
 *                       V
 * |--------------------------------------------|
 * |                                            |
 * ApplicationType| PageNo| PageName| ToolString
 *         3          3        16        any #of bytes
 *
 * 
 * A single data packet consists of a Header and Payload. Header consists of:
 * 
 * Session Id:
 *  all the participants belong to a particular session. Data is sent and received
 *  within the same session
 * 
 * Tracking Id:
 *  Each participant is assigned a unique tracking id when connected.
 * 
 * User Id:
 *  Each participant is assigned a unique user id througout the application
 * 
 * Role:
 *  Moderator, Member etc
 * 
 * PayloadLength:
 *  PayloadLength is the amount of actual data containing toolstring or other GUI related information
 * 
 * Dummy1, Dummy2:
 *  for future use
 * 
 * Application Type:
 *  WhiteBoard, MessageBoard, Participant Info etc
 * 
 * PageNo
 *   Page No if applicable
 * 
 * PageName
 *   Page name if applicable
 * 
 * ToolString
 *   data to draw a tool or any other GUI related
 * 
 * When a new client is connected, this Thread sends HandShake information to the client.
 * HandShake data mainly consists of tracking id. This tracking id is passed between client
 * and Server for each data packet. Then offline data if present is sent.
 * 
 * This Thread then continuously reads Header information from the data and determines the payload length.
 * The payload length number of bytes are read following header. Then, the clients participating
 * in the same session are retrieved and data is sent to all the clients. At the sametime, the data is also written
 * to a file for persistence.
 * @author  Anil K Nellutla 
 * @version 1.0
 */
public class RequestProcessor implements Runnable {

	// socket connection
	private Socket connection;

	// variable to check if the user is logged in
	private boolean isLogged;

	// session id
	private int sessionId;

	// unique id which is passed between participant and server
	private long trackingId;

	// user id
	private int userId;

	// login id
	private String loginId;

	// role
	private int role;

	// output stream to save offline data from participant
	private DataOutputStream fos;

	// Hashtable to hold all the sessions
	private static Hashtable sessions = new Hashtable();

	/**
	 * Creates an instance of this class.
	 * @param connection socket connection reference
	 */
	public RequestProcessor(Socket connection) {
		this.connection = connection;		
	}

	/** 
	 * Overridden method to serve all the Clients connected
	 * @see java.lang.Runnable#run()
	 */
	public void run() {
		// raw InputStream to read bytes
		InputStream rawInput;
		try {
			// create an object of InputStream
			rawInput = new BufferedInputStream(connection.getInputStream());
		} catch (IOException ex) {
			return;
		}		
		// continuously serve all the Clients connected
		while (true) {
			try {
				// read header data
				StringBuffer header = new StringBuffer();
				int c;
				for (int i = 0; i < ScoolConstants.HEADER_LENGTH; i++) {
					c = rawInput.read();
					c = (c >= 0) ? c : 256 + c;
					header.append((char) c);
				}
				String headerString = header.toString();
				System.out.println("\n" + headerString);

				// check if the participant is already logged in
				if (!isLogged) {
					// if not logged initialize Particpant and send HandShake data
					init(headerString);
					/* create a HandShake data to send to Participant. HandShake data mainly
					 * consists of tracking id
					 */
					byte[] handShakeData = createHandShakeData();
					System.out.println(
						"Sending hand shake data to the participant:\n");
					// send HandShake data
					sendToSender(handShakeData);
					// send offline data if present. This data is usually present
					// if the client logs after the session has actually started
					sendOffLineData();

					byte[] participantInfo = createParticipantInfo();
					sendToSender(participantInfo);
					sendData(participantInfo);
					saveData(participantInfo);

					// participant has logged in
					isLogged = true;
				} else {
					//get the payload data
					int bytesToRead = Utility.getPayLoadLength(headerString);
					System.out.println("payload length:" + bytesToRead);
					int bytesRead = 0;
					// byte array to hold pay load
					byte[] payLoad = new byte[bytesToRead];
					while (bytesRead < bytesToRead) {

						int result =
							rawInput.read(
								payLoad,
								bytesRead,
								bytesToRead - bytesRead);
						if (result == -1) {
							break;
						}
						bytesRead += result;
					}
					byte[] headerByteArray = (headerString).getBytes();
					System.out.println(
						"header length:" + headerByteArray.length);					

					sendData(headerByteArray);
					sendData(payLoad);

					saveData(headerByteArray);
					saveData(payLoad);
				}
			} catch (IOException ex) {
				System.out.println(
					"loginId:"
						+ loginId
						+ ", trackingId:"
						+ trackingId
						+ " logged off.");
				//ex.printStackTrace();

				try {
					if (connection != null) {
						removeParticipantFromSession();
						connection.close();
					}
				} catch (IOException ioex) {
					ioex.printStackTrace();
				}
				try {
					if (fos != null) {
						fos.close();
					}
				} catch (IOException ioex) {
					ioex.printStackTrace();
				}
				return;
			} catch (Exception ex) {
				ex.printStackTrace();
				return;
			}
		}
	}

	/**
	 * This method checks for initial paramters like userid, session name
	 * @param headerString This string holds the header sent from the client
	 */

	private void init(String headerString) {
		try {
			// the session with which participant is associated
			sessionId = Integer.parseInt(headerString.substring(0, 8));
			System.out.println("Session Id:" + sessionId);

			// create tracking id for the participant. This key is passed
			// back and forth between Server and participant.
			trackingId = Utility.createTrackingId();
			System.out.println("Tracking Id:" + trackingId);

			// userId
			userId = Integer.parseInt(headerString.substring(24, 32));
			System.out.println("User Id:" + userId);

			// role
			role = Integer.parseInt(headerString.substring(32, 34));
			System.out.println("Role:" + role);

			loginId = Utility.getLoginId(userId);

			// create a Participant object
			Participant participant =
				new Participant(userId, loginId, role, trackingId, connection);

			Object obj = sessions.get(String.valueOf(sessionId));
			// check if the session already exists
			if (obj == null) {
				System.out.println(
					"No particpant till now has joined the session.");
				// if it doesnt exist create a new session with sessionId
				// and participant
				Vector participants = new Vector();
				participant.setConnected(true);
				participants.add(participant);
				sessions.put(String.valueOf(sessionId), participants);
			} else {
				// if the session already exists add the participant
				// to the vector of participants
				Vector participants = (Vector) obj;

				//check if the participant has already logged in
				if (participants != null) {
					for (int i = 0; i < participants.size(); i++) {
						Participant partcpnt =
							(Participant) participants.get(i);
						if (partcpnt.getUserId() == userId) {
							System.out.println(
								"\n" + userId + " logged in again\n");
							disconnectParticipant(
								partcpnt.getConnection(),
								partcpnt.getTrackingId());

							synchronized (partcpnt) {
								while (partcpnt.isConnected()) {
									try {
										partcpnt.wait();
									} catch (InterruptedException ex) {
									}
								}
							}
							break;
						}
					}
				}

				System.out.println(
					"There are already "
						+ participants.size()
						+ " participant(s) in this session.");
				participant.setConnected(true);
				participants.add(participant);
			}

		} catch (StringIndexOutOfBoundsException ex) {

		} catch (ArrayIndexOutOfBoundsException ex) {
		}
	}

	/**
	 * This method creates a Hand Shake Command String which is sent to
	 * the participant when logged in for the first time
	 * @return
	 */
	private byte[] createHandShakeData() {
		StringBuffer handShakeData = new StringBuffer();
		handShakeData.append(Utility.convertTo8Byte(sessionId));
		handShakeData.append(Utility.convertTo16Byte(trackingId));
		handShakeData.append(Utility.convertTo8Byte(userId));
		handShakeData.append(Utility.convertTo2Byte(role));
		handShakeData.append(
			Utility.convertTo8Byte(ScoolConstants.HANDSHAKE_PAYLOAD_LENGTH));
		handShakeData.append(Utility.convertTo8Byte(0));
		handShakeData.append(Utility.convertTo8Byte(0));

		handShakeData.append(Utility.convertTo16Byte(loginId));
		handShakeData.append(ScoolConstants.HANDSHAKE_PAYLOAD);

		return handShakeData.toString().getBytes();
	}

	private byte[] createParticipantInfo() {
		String participantInfoPayLoad =
			Utility.convertTo3Byte(ScoolConstants.PARTICIPANTS_INFO_APP)
				+ Utility.convertTo3Byte(0)
				+ Utility.convertTo16Byte("")
				+ Utility.getNewParticipantJoinedToolString(loginId, role);

		StringBuffer participantInfo = new StringBuffer();
		participantInfo.append(Utility.convertTo8Byte(sessionId));
		participantInfo.append(Utility.convertTo16Byte(trackingId));
		participantInfo.append(Utility.convertTo8Byte(userId));
		participantInfo.append(Utility.convertTo2Byte(role));
		participantInfo.append(
			Utility.convertTo8Byte(participantInfoPayLoad.length()));
		participantInfo.append(Utility.convertTo8Byte(0));
		participantInfo.append(Utility.convertTo8Byte(0));

		participantInfo.append(participantInfoPayLoad);

		return participantInfo.toString().getBytes();
	}

	private byte[] deleteParticipantInfo() {
		String delteParticipantInfoPayLoad =
			Utility.convertTo3Byte(ScoolConstants.PARTICIPANTS_INFO_APP)
				+ Utility.convertTo3Byte(0)
				+ Utility.convertTo16Byte("")
				+ Utility.getDeleteParticipantInfoToolString(loginId, role);

		StringBuffer participantInfo = new StringBuffer();
		participantInfo.append(Utility.convertTo8Byte(sessionId));
		participantInfo.append(Utility.convertTo16Byte(trackingId));
		participantInfo.append(Utility.convertTo8Byte(userId));
		participantInfo.append(Utility.convertTo2Byte(role));
		participantInfo.append(
			Utility.convertTo8Byte(delteParticipantInfoPayLoad.length()));
		participantInfo.append(Utility.convertTo8Byte(0));
		participantInfo.append(Utility.convertTo8Byte(0));

		participantInfo.append(delteParticipantInfoPayLoad);
		return participantInfo.toString().getBytes();
	}

	private byte[] disconnectParticipant(long trackingId) {
		String disConnectParticipantPayLoad =
			Utility.convertTo3Byte(ScoolConstants.PARTICIPANTS_INFO_APP)
				+ Utility.convertTo3Byte(0)
				+ Utility.convertTo16Byte("")
				+ Utility.getDisconnectParticipantToolString(loginId, role);

		StringBuffer participantInfo = new StringBuffer();
		participantInfo.append(Utility.convertTo8Byte(sessionId));
		participantInfo.append(Utility.convertTo16Byte(trackingId));
		participantInfo.append(Utility.convertTo8Byte(userId));
		participantInfo.append(Utility.convertTo2Byte(role));
		participantInfo.append(
			Utility.convertTo8Byte(disConnectParticipantPayLoad.length()));
		participantInfo.append(Utility.convertTo8Byte(0));
		participantInfo.append(Utility.convertTo8Byte(0));

		participantInfo.append(disConnectParticipantPayLoad);
		return participantInfo.toString().getBytes();
	}

	/**
	 * This method sends header string to all the particpants in the
	 * session.
	 * @param headerString
	 */
	private void sendData(byte[] rawData) {
		Object obj = sessions.get(String.valueOf(sessionId));
		if (obj != null) {
			Vector participants = (Vector) obj;
			if (participants != null) {
				for (int i = 0; i < participants.size(); i++) {
					Participant participant = (Participant) participants.get(i);
					// dont send the data to the sender participant
					if (participant.getTrackingId() != trackingId) {
						try {
							OutputStream rawOutput =
								new BufferedOutputStream(
									participant
										.getConnection()
										.getOutputStream());

							rawOutput.write(rawData);
							rawOutput.flush();
						} catch (IOException ex) {
						}
					}
				}
			}
		}
	}

	/**
	 * This method send data to the sender participant. Usually the data
	 * would be a Hand Shake string
	 * @param handShakeString
	 */
	private void sendToSender(byte[] data) {
		try {
			OutputStream rawOutput =
				new BufferedOutputStream(connection.getOutputStream());
			rawOutput.write(data);
			rawOutput.flush();
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	private void disconnectParticipant(Socket connection, long trackingId) {
		try {
			OutputStream rawOutput =
				new BufferedOutputStream(connection.getOutputStream());
			rawOutput.write(disconnectParticipant(trackingId));
			rawOutput.flush();
		} catch (IOException ex) {
		}
	}

	private void sendOffLineData() {
		String userDir = System.getProperty("user.dir");
		File sessionFile =
			new File(userDir + File.separator + sessionId + ".txt");
		if (!sessionFile.exists()) {
			try {
				sessionFile.createNewFile();
				sessionFile.deleteOnExit();
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		} else {
			try {
				DataInputStream fis =
					new DataInputStream(
						new BufferedInputStream(
							new FileInputStream(sessionFile)));
				long fileLength = sessionFile.length();
				if (fileLength > 0) {
					byte[] offlineData = new byte[(int) fileLength];
					fis.readFully(offlineData);
					fis.close();
					sendToSender(offlineData);					
				}
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}
		sendToSender(endOfOfflineData());
		try {
			fos =
				new DataOutputStream(
					new BufferedOutputStream(
						new FileOutputStream(
							userDir + File.separator + sessionId + ".txt",
							true)));
		} catch (IOException ex) {
			ex.printStackTrace();
		}

	}

	private void saveData(byte[] data) {
		// write data to a file
		try {
			fos.write(data);
			fos.flush();
			//fos.close();
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	private void removeParticipantFromSession() {
		Object obj = sessions.get(String.valueOf(sessionId));
		if (obj != null) {
			Vector participants = (Vector) obj;
			if (participants != null) {
				Participant participant = null;
				for (int i = 0; i < participants.size(); i++) {
					participant = (Participant) participants.get(i);
					if (participant.getTrackingId() == trackingId) {
						byte[] deleteParticpanteInfo = deleteParticipantInfo();
						sendData(deleteParticpanteInfo);
						saveData(deleteParticpanteInfo);
						break;
					}
				}
				synchronized (participant) {
					participant.setConnected(false);
					participant.notifyAll();
				}
				participants.remove(participant);
			}
		}
	}

	private byte[] endOfOfflineData() {
		String endOfOfflineDataPayLoad =
			Utility.convertTo3Byte(ScoolConstants.SERVER_APP)
				+ Utility.convertTo3Byte(0)
				+ Utility.convertTo16Byte("")
				+ Utility.getEndOfOfflineDataToolString(loginId);

		StringBuffer endOfOfflineData = new StringBuffer();
		endOfOfflineData.append(Utility.convertTo8Byte(sessionId));
		endOfOfflineData.append(Utility.convertTo16Byte(trackingId));
		endOfOfflineData.append(Utility.convertTo8Byte(userId));
		endOfOfflineData.append(Utility.convertTo2Byte(role));
		endOfOfflineData.append(
			Utility.convertTo8Byte(endOfOfflineDataPayLoad.length()));
		endOfOfflineData.append(Utility.convertTo8Byte(0));
		endOfOfflineData.append(Utility.convertTo8Byte(0));

		endOfOfflineData.append(endOfOfflineDataPayLoad);
		System.out.println("end of offline data tool string:"+endOfOfflineData.toString());
		return endOfOfflineData.toString().getBytes();
	}
}
