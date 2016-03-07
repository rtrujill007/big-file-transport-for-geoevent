package com.esri.geoevent.transport.bigfile;

import com.esri.ges.core.component.ComponentException;
import com.esri.ges.manager.datastore.folder.FolderDataStoreManager;
import com.esri.ges.transport.Transport;
import com.esri.ges.transport.TransportServiceBase;
import com.esri.ges.transport.util.XmlTransportDefinition;

public class BigFileInboundTransportService extends TransportServiceBase
{
	public final static String INPUT_FOLDERDATASTORE_PROPERTY = "inputFolderDataStore";
	public final static String INPUT_DIRECTORY_PROPERTY = "inputDirectory";
	public final static String INPUT_FILE_FILTER_PROPERTY = "inputFileFilter";
	public final static String INPUT_RECURSIVELY_SCAN_PROPERTY = "inputRecursivelyScan";
	public final static String INPUT_DELETE_PROCESSED_FILES_PROPERTY = "inputDeleteFiles";
	public final static String INPUT_USE_FILE_NAME_AS_CHANNEL_ID_PROPERTY = "inputUseFileNamesAsChannelID";
	public final static String INPUT_MAX_NUM_OF_LINES_PROPERTY = "inputMaxNumberOfLines";
	public final static String INPUT_FLUSH_INTERVAL_MS_PROPERTY = "inputFlushIntervalInMS";
	public final static String INPUT_IGNORE_FIRST_LINE_PROPERTY = "inputIgnoreFirstLine";
	
	public BigFileInboundTransportService()
	{
		definition = new XmlTransportDefinition(getResourceAsStream("big-inboundtransport-definition.xml"));
	}

	protected FolderDataStoreManager folderDataStoreManager;

	public void setFolderDataStoreManager(FolderDataStoreManager folderDataStoreManager)
	{
		this.folderDataStoreManager = folderDataStoreManager;
	}
	
	@Override
	public Transport createTransport() throws ComponentException
	{
		return new BigFileInboundTransport(definition, folderDataStoreManager);
	}
}
