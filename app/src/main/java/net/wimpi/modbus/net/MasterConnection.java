package net.wimpi.modbus.net;

/**
 * A common interface for master connections (not strictly covering serial connections)
 * @author bonino
 *
 */
public interface MasterConnection
{
	
	void connect() throws Exception;
	
	boolean isConnected();
	
	void close();
	
}
