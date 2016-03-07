package com.esri.geoevent.transport.bigfile;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.component.RunningState;
import com.esri.ges.core.validation.ValidationException;
import com.esri.ges.datastore.folder.FolderDataStore;
import com.esri.ges.framework.i18n.BundleLogger;
import com.esri.ges.framework.i18n.BundleLoggerFactory;
import com.esri.ges.manager.datastore.folder.FolderDataStoreManager;
import com.esri.ges.transport.InboundTransportBase;
import com.esri.ges.transport.TransportDefinition;

public class BigFileInboundTransport extends InboundTransportBase implements Runnable
{
	private static final BundleLogger LOGGER = BundleLoggerFactory.getLogger(BigFileInboundTransport.class);

	// These are boilerplate variables used by most Transport implementations
	private String errorMessage;

	// These are the values that come in as properties from the user
	private File		directory;			// The directory where the input files are expected to be stored
	private boolean	recursive;			// if true, then this transport should look in all sub-folders of the input directory.
	private boolean	deleteFiles;		// if true, we will delete files after reading them.
	private String	filenameFilter;	// The filename pattern used to select which files in the input folder we are reading.

	// The following three variables "running", "stopping", and "thread" are
	// protected by synchronized methods
	// to control access to anything affecting the control of the i/o thread.
	private volatile boolean	running							= false;
	private volatile boolean	stopping						= false;
	private Thread						thread;
	private ByteBuffer				buffer							= ByteBuffer.allocate(1024 * 1024 * 10);	// 1024 * 1024 * 10 = 10 MB
	private int								numberOfLinesToRead	= 1000;
	private long							flushIntervalInMS		= 500;
	private boolean						ignoreFirstLine			= false;
	
	// These are the internal variables use for processing input files
	private File					currentFile;
	private List<String>	processedFiles	= new ArrayList<String>();
	private List<String>	pendingFiles		= new ArrayList<String>();

	// vars to interact with FolderDataStore's
	private FolderDataStoreManager folderDataStoreManager;

	public BigFileInboundTransport(TransportDefinition definition, FolderDataStoreManager manager) throws ComponentException
	{
		super(definition);
		this.definition = definition;
		this.folderDataStoreManager = manager;
	}

	protected void applyProperties()
	{
		String folderDataStoreName = getProperty(BigFileInboundTransportService.INPUT_FOLDERDATASTORE_PROPERTY).getValueAsString();
		if (folderDataStoreName == null)
		{
			throw new RuntimeException(LOGGER.translate("PROP_IS_REQUIRED", BigFileInboundTransportService.INPUT_FOLDERDATASTORE_PROPERTY));
		}
		FolderDataStore folderDataStore = folderDataStoreManager.getFolderDataStore(folderDataStoreName);
		if (folderDataStore == null)
		{
			throw new RuntimeException(LOGGER.translate("INVALID_FOLDER_DS", folderDataStoreName));
		}

		String directoryname = (String) getProperty(BigFileInboundTransportService.INPUT_DIRECTORY_PROPERTY).getValue();
		filenameFilter = (String) getProperty(BigFileInboundTransportService.INPUT_FILE_FILTER_PROPERTY).getValue();
		recursive = ((Boolean) getProperty(BigFileInboundTransportService.INPUT_RECURSIVELY_SCAN_PROPERTY).getValue()).booleanValue();
		deleteFiles = ((Boolean) getProperty(BigFileInboundTransportService.INPUT_DELETE_PROCESSED_FILES_PROPERTY).getValue()).booleanValue();
		if (hasProperty(BigFileInboundTransportService.INPUT_MAX_NUM_OF_LINES_PROPERTY))
		{
			numberOfLinesToRead = (Integer) getProperty(BigFileInboundTransportService.INPUT_MAX_NUM_OF_LINES_PROPERTY).getValue();
		}
		if (hasProperty(BigFileInboundTransportService.INPUT_FLUSH_INTERVAL_MS_PROPERTY))
		{
			flushIntervalInMS = ((Integer) getProperty(BigFileInboundTransportService.INPUT_FLUSH_INTERVAL_MS_PROPERTY).getValue()).longValue();
		}
		if (hasProperty(BigFileInboundTransportService.INPUT_IGNORE_FIRST_LINE_PROPERTY))
		{
			ignoreFirstLine = ((Boolean) getProperty(BigFileInboundTransportService.INPUT_IGNORE_FIRST_LINE_PROPERTY).getValue()).booleanValue();
		}
		directory = new File(folderDataStore.getPath(), directoryname);
	}

	private void populatePendingFiles(File currentDirectory)
	{
		String[] files = currentDirectory.list();
		if (files == null)
			return;
		for (String file : files)
		{
			File newFile = new File(currentDirectory.getAbsolutePath() + File.separator + file);
			if (newFile.isDirectory())
			{
				if (recursive)
					populatePendingFiles(newFile);
			}
			else
			{
				String newFileName = newFile.getName();
				if (filenameFilter.length() == 0 || newFileName.matches(filenameFilter))
				{
					String newFileAbsolutePath = newFile.getAbsolutePath().intern();
					pendingFiles.add(newFileAbsolutePath);
				}
			}
		}
	}

	protected void readBuffer()
	{
		if (currentFile == null)
		{
			currentFile = getNextFile();
			if (currentFile == null)
			{
				try
				{
					Thread.sleep(100);
				}
				catch (InterruptedException ex)
				{
					return;
				}
				return;
			}
		}

		// read the files by line
		long startTime = 0L;
		try (Stream<String> lines = Files.lines(Paths.get(currentFile.getPath())))
		{
			LOGGER.info("START_READING_FILE", currentFile.getName());
			startTime = System.currentTimeMillis();
			int counter = 0;
			for (String line : (Iterable<String>) lines::iterator)
			{
				if(!running || stopping)
					break;
				
				// do we ignore the first line ?
				if ( ! (ignoreFirstLine && counter == 0))
				{
					buffer.put(line.getBytes());
					buffer.put(System.lineSeparator().getBytes());
				}
				counter++;
				// process each nth line
				if (buffer.hasRemaining() && counter % numberOfLinesToRead == 0)
				{
					buffer.flip();
					byteListener.receive(buffer, currentFile.getName());
					buffer.compact();
					LOGGER.info("DONE_READING_LINES", counter, currentFile.getName());
					// allow the adapter to breath
					try
					{
						Thread.sleep(flushIntervalInMS);
					}
					catch (InterruptedException ignored)
					{
					}
				}
			}

			if(!running || stopping)
			{
				buffer.clear();
				currentFile = null;
				return;
			}
			// finish off the reading of the file
			buffer.flip();
			if (buffer.hasRemaining())
			{
				byteListener.receive(buffer, currentFile.getName());
			}
			buffer.clear();
			if (deleteFiles)
			{
				processedFiles.remove(currentFile);
				Files.delete(currentFile.toPath());
			}
			long endTime = System.currentTimeMillis() - startTime;
			LOGGER.info("DONE_READING_FILE", counter, currentFile.getName(), endTime);
			currentFile = null;
		}
		catch (IOException ex)
		{
			LOGGER.error("ERROR_READING_FILE", currentFile.getName(), ex.getMessage());
			LOGGER.info(ex.getMessage(), ex);
			currentFile = null;
			return;
		}
	}

	private File getNextFile()
	{
		pendingFiles.clear();
		populatePendingFiles(directory);

		for (String file : pendingFiles)
		{
			if (!processedFiles.contains(file))
			{
				File f = new File(file);
				processedFiles.add(f.getAbsolutePath());
				return f;
			}
		}
		return null;
	}

	protected void cleanup()
	{
		pendingFiles.clear();
		processedFiles.clear();
		currentFile = null;
	}

	@Override
	public synchronized void start()
	{
		if (running || stopping)
			return;
		running = true;
		setRunningState(RunningState.STARTING);
		thread = new Thread(this);
		thread.start();
	}

	@Override
	public synchronized void stop()
	{
		if (!running || stopping)
			return;
		stopping = true;
		running = false;
		setRunningState(RunningState.STOPPING);
	}

	private synchronized void reset()
	{
		thread = null;
		stopping = false;
		running = false;
		if (errorMessage != null)
			setRunningState(RunningState.ERROR);
		else
			setRunningState(RunningState.STOPPED);

	}

	@Override
	public synchronized boolean isRunning()
	{
		return running;
	}

	public String getErrorMessage()
	{
		return errorMessage;
	}

	@Override
	public void validate() throws ValidationException
	{
		List<String> errors = new ArrayList<String>();
		try
		{
			super.validate();
		}
		catch (ValidationException e)
		{
			errors.add(e.getMessage());
		}

		String ds = getProperty("inputFolderDataStore").getValueAsString();
		if (folderDataStoreManager.getFolderDataStore(ds) == null)
		{
			errors.add(LOGGER.translate("FOLDER_DS_NOT_REGISTERED"));
		}

		if (errors.size() > 0)
		{
			StringBuffer sb = new StringBuffer();
			for (String message : errors)
				sb.append(message).append("\n");

			throw new ValidationException(LOGGER.translate("VALIDATION_ERROR", sb.toString()));
		}
	}

	@Override
	public void run()
	{
		try
		{
			errorMessage = null;
			applyProperties();
			setRunningState(RunningState.STARTED);
			while (running && !stopping)
			{
				try
				{
					readBuffer();
				}
				catch (Exception e)
				{
					errorMessage = LOGGER.translate("READ_BUFFER_ERROR", e.getMessage());
					LOGGER.error(errorMessage);
					LOGGER.info(e.getMessage(), e);
					stop();
				}
			}
			cleanup();
		}
		catch (Exception ex)
		{
			errorMessage = LOGGER.translate("READ_BUFFER_ERROR", ex.getMessage());
			LOGGER.error(errorMessage);
			LOGGER.info(ex.getMessage(), ex);
		}
		reset();
	}

	@Override
	public boolean isClusterable()
	{
		return false;
	}
}
