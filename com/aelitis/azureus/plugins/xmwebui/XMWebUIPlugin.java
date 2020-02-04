/*
 * Created on Sep 16, 2009
 * Created by Paul Gardner
 * 
 * Copyright 2009 Vuze, Inc.  All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */


package com.aelitis.azureus.plugins.xmwebui;

import java.io.*;
import java.net.*;
import java.text.DateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.gudy.bouncycastle.util.encoders.Base64;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import com.aelitis.azureus.plugins.remsearch.RemSearchPluginPageGenerator;
import com.aelitis.azureus.plugins.remsearch.RemSearchPluginPageGeneratorAdaptor;
import com.aelitis.azureus.plugins.remsearch.RemSearchPluginSearch;
import com.biglybt.core.Core;
import com.biglybt.core.CoreFactory;
import com.biglybt.core.category.Category;
import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.download.DownloadManager;
import com.biglybt.core.global.GlobalManager;
import com.biglybt.core.internat.MessageText;
import com.biglybt.core.logging.LogAlert;
import com.biglybt.core.logging.Logger;
import com.biglybt.core.metasearch.*;
import com.biglybt.core.metasearch.impl.web.WebEngine;
import com.biglybt.core.pairing.PairingManager;
import com.biglybt.core.pairing.PairingManagerFactory;
import com.biglybt.core.security.CryptoManager;
import com.biglybt.core.subs.*;
import com.biglybt.core.tag.*;
import com.biglybt.core.torrent.PlatformTorrentUtils;
import com.biglybt.core.torrent.TOTorrent;
import com.biglybt.core.torrent.TOTorrentFactory;
import com.biglybt.core.tracker.TrackerPeerSource;
import com.biglybt.core.util.*;
import com.biglybt.core.vuzefile.VuzeFile;
import com.biglybt.core.vuzefile.VuzeFileComponent;
import com.biglybt.core.vuzefile.VuzeFileHandler;
import com.biglybt.pifimpl.local.PluginCoreUtils;
import com.biglybt.pifimpl.local.torrent.TorrentImpl;
import com.biglybt.pifimpl.local.utils.resourcedownloader.ResourceDownloaderFactoryImpl;
import com.biglybt.ui.webplugin.WebPlugin;
import com.biglybt.util.JSONUtils;
import com.biglybt.util.MapUtils;
import com.biglybt.util.PlayUtils;

import com.biglybt.pif.*;
import com.biglybt.pif.config.ConfigParameter;
import com.biglybt.pif.config.ConfigParameterListener;
import com.biglybt.pif.disk.DiskManagerFileInfo;
import com.biglybt.pif.download.*;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.torrent.*;
import com.biglybt.pif.tracker.web.TrackerWebPageRequest;
import com.biglybt.pif.tracker.web.TrackerWebPageResponse;
import com.biglybt.pif.ui.UIInstance;
import com.biglybt.pif.ui.UIManagerListener;
import com.biglybt.pif.ui.config.*;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.update.*;
import com.biglybt.pif.utils.Utilities;
import com.biglybt.pif.utils.Utilities.JSONServer;
import com.biglybt.pif.utils.resourcedownloader.ResourceDownloader;
import com.biglybt.pif.utils.resourcedownloader.ResourceDownloaderAdapter;

import static com.aelitis.azureus.plugins.xmwebui.TransmissionVars.*;

@SuppressWarnings({
		"unchecked",
		"rawtypes"
		, "unused"})
public class 
XMWebUIPlugin
	extends WebPlugin
	implements UnloadablePlugin, DownloadManagerListener, DownloadWillBeAddedListener
{	
	
	/**
	 * <pre>
	 * 5: No longer xml escapes strings when user agent does not start with "Mozilla/"
	 *
	 * 6: handle more of session-set
	 *
	 * 7: Bandwidth reduction:
	 *      * files-hc-<id> can now be a single comma delimited string
	 *      * mapPerFile request flag:
	 *          true: default. each file returned is a map
	 *          false: Each file is an ordered array instead of a map.
	 *                 They keys to the array are sent as "fileKeys"
	 *    "flagStr" in peers map is mostly implemented
	 *    
	 * 8: 
	 *    * change "speedHistory" field of "torrent-get" return value from:
	 *      [ [ send-bytes, receive-bytes, swarm-bytes] , [...] ]
	 *      to:
	 *        [ 
	 *           { "timestamp" : unix-timestamp, "upload" : send-bytes , "download" : receive-bytes, "swarm" : swarm-bytes },
	 *           ...
	 *        ]
	 *    * Add "peer-fields" to torrent-get, allowing ability to limit fields 
	 *      sent from "peers" field 
	 * </pre>
	 */
	public static final int VUZE_RPC_VERSION = 8;

	private static Download
	destubbify(
		DownloadStub	stub )
	
		throws DownloadException
	{
		return( stub.destubbify());
	}
	
		// end stuff
	
    public static final int DEFAULT_PORT    = 9091;

    private static Properties defaults = new Properties();

    static{
    	System.setProperty( "az.xmwebui.skip.ssl.hack", "true" );
    	
        defaults.put( WebPlugin.PR_DISABLABLE, Boolean.TRUE);
        defaults.put( WebPlugin.PR_ENABLE, Boolean.TRUE);
        defaults.put( WebPlugin.PR_PORT, DEFAULT_PORT);
        defaults.put( WebPlugin.PR_ROOT_DIR, "transmission/web" );
        defaults.put( WebPlugin.PR_ENABLE_KEEP_ALIVE, Boolean.TRUE);
        defaults.put( WebPlugin.PR_HIDE_RESOURCE_CONFIG, Boolean.TRUE);
        defaults.put( WebPlugin.PR_PAIRING_SID, "xmwebui" );
    }

    private static final String	SEARCH_PREFIX 	= "/psearch";
    private static final int	SEARCH_TIMEOUT	= 60*1000;

		protected static final long SEARCH_AUTOREMOVE_TIMEOUT = 60 * 1000 * 60L;
    
    private boolean	view_mode;
    
    protected BooleanParameter trace_param;
    
    private BooleanParameter hide_ln_param;
    private BooleanParameter force_net_param;
    private DirectoryParameter webdir_param;
    private HyperlinkParameter openui_param;
    private HyperlinkParameter launchAltWebUI_param;

    private TorrentAttribute	t_id;
    
    private final Map<Long,RecentlyRemovedData>	recently_removed 	= new HashMap<>();
    
    private Collection<Long> stubbifying			= new HashSet<>();
    
    private final Map<String, String> ip_to_session_id = new HashMap<>();
    
    private RemSearchPluginPageGenerator search_handler;
    private TimerEventPeriodic				search_timer;
    
    private boolean							check_ids_outstanding = true;
    
    private final Map<String,SearchInstance>	active_searches = new HashMap<>();
    private final Map<String,TagSearchInstance>	active_tagsearches = new HashMap<>();
    
    private final Object			lifecycle_lock = new Object();
    private int 			lifecycle_state = 0;
    private boolean			update_in_progress;
    
    private final List<MagnetDownload>		magnet_downloads = new ArrayList<>();
    
    private Object json_rpc_client;	// Object during transition to core support
    
    final Object							json_server_method_lock 	= new Object();
    transient Map<String,Object>	json_server_methods 		= new HashMap<>();		// Object during transition to core support

		private BooleanParameter logtofile_param;

		private LoggerChannel log;

		final Map<Long, Object> referenceKeeper = new LinkedHashMap<>();
    
    public
    XMWebUIPlugin()
    {
    	super( defaults );
    	
    	search_handler = 
			new RemSearchPluginPageGenerator(
					new RemSearchPluginPageGeneratorAdaptor()
					{
						@Override
						public void
						searchReceived(
							String originator )
								
							throws IOException 
						{
						}
							
						@Override
						public void
						searchCreated(
							RemSearchPluginSearch search )
						{
						}
						
						@Override
						public void
						log(
							String str )
						{
							XMWebUIPlugin.this.log( str );
						}
						
						@Override
						public void
						log(
							String 		str,
							Throwable 	e )
						{
							XMWebUIPlugin.this.log( str, e );
						}
					},
					SEARCH_PREFIX,
					null,
					16,
					100,
					false );
    }
    
	@Override
	public void
	initialize(
		PluginInterface _plugin_interface )
	
		throws PluginException
	{	
		
		plugin_interface	= _plugin_interface;

		log = plugin_interface.getLogger().getChannel( "xmwebui" );
		defaults.put(PR_LOG, log);
		
		final PluginConfig pluginconfig = plugin_interface.getPluginconfig();
		
		if (	!PairingManagerFactory.getSingleton().isEnabled() && 
				!pluginconfig.hasPluginParameter("Password")
				&& !pluginconfig.hasPluginParameter("Password Enable")){
			
			pluginconfig.setPluginParameter("Config Migrated", true);
			
			pluginconfig.setPluginParameter("Password Enable", true);
			
			SHA1Hasher hasher = new SHA1Hasher();
			
			pluginconfig.setPluginParameter("Password",
					hasher.calculateHash(RandomUtils.nextSecureHash()));
		}

		super.initialize( _plugin_interface );

		plugin_interface.getUtilities().getLocaleUtilities().integrateLocalisedMessageBundle(
				"com.aelitis.azureus.plugins.xmwebui.internat.Messages" );
		
		t_id = plugin_interface.getTorrentManager().getPluginAttribute( "xmui.dl.id" );
		
		BasicPluginConfigModel config = getConfigModel();
			
		config.addLabelParameter2( "xmwebui.blank" );

		openui_param = config.addHyperlinkParameter2("xmwebui.openui", "");

		config.addLabelParameter2( "xmwebui.blank" );

		/////

		webdir_param = config.addDirectoryParameter2("xmwebui.web.dir",
				"xmwebui.alternate.ui.dir", "");
		launchAltWebUI_param = config.addHyperlinkParameter2(
				"xmwebui.openui", "");
		ParameterListener webdir_param_listener = new ParameterListener() {
			@Override
			public void parameterChanged(Parameter param) {
				String val = ((DirectoryParameter) param).getValue();
				launchAltWebUI_param.setEnabled(
						val != null && new File(val).isDirectory());
			}
		};
		webdir_param.addListener(webdir_param_listener);
		webdir_param_listener.parameterChanged(webdir_param);
		config.createGroup("xmwebui.alternate.ui.group", webdir_param,
				launchAltWebUI_param);

		//////

		config.addLabelParameter2( "xmwebui.blank" );

		hide_ln_param = config.addBooleanParameter2( "xmwebui.hidelownoise", "xmwebui.hidelownoise", true );

		force_net_param = config.addBooleanParameter2( "xmwebui.forcenets", "xmwebui.forcenets", false );

		trace_param = config.addBooleanParameter2( "xmwebui.trace", "xmwebui.trace", false );
		
		logtofile_param = config.addBooleanParameter2( "xmwebui.logtofile", "xmwebui.logtofile", false );

		changeLogToFile(logtofile_param.getValue());
		logtofile_param.addConfigParameterListener(new ConfigParameterListener() {
			@Override
			public void configParameterChanged(ConfigParameter param) {
				changeLogToFile(logtofile_param.getValue());
			}
		});

		/////

		ConfigParameter bindip_param = pluginconfig.getPluginParameter(WebPlugin.CONFIG_BIND_IP);
		if (bindip_param != null) {
			bindip_param.addConfigParameterListener(new ConfigParameterListener() {
				@Override
				public void configParameterChanged(ConfigParameter param) {
					updateConfigLaunchParams();
				}
			});
		}
		ConfigParameter port_param = pluginconfig.getPluginParameter(WebPlugin.CONFIG_PORT);
		if (port_param != null) {
			port_param.addConfigParameterListener(new ConfigParameterListener() {
				@Override
				public void configParameterChanged(ConfigParameter param) {
					updateConfigLaunchParams();
				}
			});
		}
		updateConfigLaunchParams();

		ConfigParameter mode_parameter = pluginconfig.getPluginParameter( WebPlugin.CONFIG_MODE );

		if ( mode_parameter == null ){

			view_mode = true;

			checkViewMode();

		}else{

			mode_parameter.addConfigParameterListener(
				new ConfigParameterListener()
				{
					@Override
					public void
					configParameterChanged(
						ConfigParameter param )
					{
						setViewMode();
					}
				});

			setViewMode();
		}

		com.biglybt.pif.download.DownloadManager dm = plugin_interface.getDownloadManager();
		
		dm.addDownloadWillBeAddedListener( this );

		dm.addListener( this );
		
		dm.addDownloadStubListener(
				event -> {
					int	event_type = event.getEventType();

					List<DownloadStub> stubs = event.getDownloadStubs();

					synchronized( recently_removed ){

						switch (event_type) {
							case DownloadStubEvent.DSE_STUB_WILL_BE_ADDED:

								for (DownloadStub stub : stubs) {

									try {
										long id = destubbify(stub).getLongAttribute(t_id);

										stubbifying.add(id);

										stub.setLongAttribute(t_id, id);

									} catch (Throwable e) {

										Debug.out(e);
									}
								}

								break;
							case DownloadStubEvent.DSE_STUB_ADDED:
							case DownloadStubEvent.DSE_STUB_WILL_BE_REMOVED:

								for (DownloadStub stub : stubs) {

									long id = stub.getLongAttribute(t_id);

									stubbifying.remove(id);
								}
								break;
						}
					}
				}, false );
		
		search_timer = SimpleTimer.addPeriodicEvent(
			"XMSearchTimeout",
			30*1000,
				event -> {
					Map<String,RemSearchPluginSearch> searches = search_handler.getSearches();

					for (RemSearchPluginSearch search : searches.values()) {

						if (search.getAge() > SEARCH_TIMEOUT) {

							log("Timeout: " + search.getString());

							search.destroy();
						}
					}
					
					synchronized( active_searches ){
						
						Iterator<SearchInstance> it2 = active_searches.values().iterator();
						
						while( it2.hasNext()){
							
							SearchInstance search = it2.next();
							
							if (search.isComplete() && search.getLastResultsAgo() > SEARCH_AUTOREMOVE_TIMEOUT) {
								it2.remove();
							}
							
							if ( !search.isComplete() && search.getAge() > SEARCH_TIMEOUT ){
								
								log( "Timeout: " + search.getString());
								
								search.failWithTimeout();
							}	
						}
					}
					
					cleanupReferenceKeeper();
				});
		
		plugin_interface.addListener(
			new PluginAdapter()
			{
				@Override
				public void 
				initializationComplete() 
				{
					synchronized ( lifecycle_lock ){
					
						if ( lifecycle_state == 0 ){
					
							lifecycle_state = 1;
						}
					}
				}
			});
		
		plugin_interface.getUIManager().addUIListener(
			new UIManagerListener()
			{
				@Override
				public void
				UIAttached(
					UIInstance		instance )
				{
					if ( instance.getUIType().equals(UIInstance.UIT_SWT) ){
						
						try{
							Class.forName( "com.aelitis.azureus.plugins.xmwebui.swt.XMWebUIPluginView").getConstructor(
								new Class[]{ XMWebUIPlugin.class, UIInstance.class }).newInstance(
									XMWebUIPlugin.this, instance);
														
						}catch( Throwable e ){
							e.printStackTrace();
						}
					}
				}
				
				@Override
				public void
				UIDetached(
					UIInstance		instance )
				{
				}
			});
					
		json_rpc_client = 
			new Utilities.JSONClient() {
				
				@Override
				public void
				serverRegistered(
					JSONServer server ) 
				{
					List<String> methods = server.getSupportedMethods();

					//System.out.println( "Registering methods: " + server.getName() + " -> " + methods );

					synchronized( json_server_method_lock ){
						
						Map<String,Object> new_methods = new HashMap<>(json_server_methods);
													
						for ( String method: methods ){
							
							new_methods.put( method, server );
						}
						
						json_server_methods = new_methods;
					}
				}
				
				@Override
				public void
				serverUnregistered(
					JSONServer server ) 
				{
					List<String> methods = server.getSupportedMethods();
					
					//System.out.println( "Unregistering methods: " + server.getName() + " -> " + methods );

					synchronized( json_server_method_lock ){
						
						Map<String,Object> new_methods = new HashMap<>(json_server_methods);
													
						for ( String method: methods ){
							
							new_methods.remove( method );
						}
						
						json_server_methods = new_methods;
					}
				}
			};
			
		plugin_interface.getUtilities().registerJSONRPCClient((Utilities.JSONClient)json_rpc_client );
	}

	private void cleanupReferenceKeeper() {
		synchronized (referenceKeeper) {
			long breakTime = SystemTime.getOffsetTime(1000 * -60);
			for (Iterator<Long> iterator = referenceKeeper.keySet().iterator(); iterator.hasNext(); ) {
				Long timestamp = iterator.next();
				if (timestamp < breakTime) {
					iterator.remove();
				} else {
					break;
				}
			}
		}
	}

	private void updateConfigLaunchParams() {
		PluginConfig pc = plugin_interface.getPluginconfig();

		String bindIP = pc.getPluginStringParameter(WebPlugin.CONFIG_BIND_IP,
				CONFIG_BIND_IP_DEFAULT);
		if (bindIP.isEmpty()) {
			bindIP = "127.0.0.1";
		}
		int port = pc.getPluginIntParameter(WebPlugin.CONFIG_PORT,
				CONFIG_PORT_DEFAULT);
		launchAltWebUI_param.setHyperlink(
				"http://" + bindIP + ":" + port + "/transmission/web");
		openui_param.setHyperlink("http://" + bindIP + ":" + port + "/");
	}

	protected void changeLogToFile(boolean logToFile) {
		if (log != null) {
			if (logToFile) {
				log.setDiagnostic(1024L * 1024L, true);
			} else {
				// no way of turning off :(
			}
		}
	}

	private void
	checkViewMode()
	{
		if ( view_mode ){
			
			return;
		}
		
		PluginConfig pc = plugin_interface.getPluginconfig();
		
		{
			String 	data_dir 	= pc.getCoreStringParameter( PluginConfig.CORE_PARAM_STRING_DEFAULT_SAVE_PATH );
	
			boolean	data_bad;
			
			if ( data_dir == null || data_dir.length() == 0 ){
				
				data_bad = true;
				
			}else{
				
				File dir = new File( data_dir );
				
				if ( !dir.exists()){
					
					dir.mkdirs();
				}
				
				data_bad = !dir.canWrite();
			}
				
			if ( data_bad ){
				
				Logger.log(
					new LogAlert(
						true,
						LogAlert.AT_ERROR,
						MessageText.getString( "xmwebui.error.data_path" )));	
			}
		}
		
		if ( !pc.getUnsafeBooleanParameter( "Save Torrent Files" )){
			
			Logger.log(
					new LogAlert(
						true,
						LogAlert.AT_ERROR,
						MessageText.getString( "xmwebui.error.torrent_path" )));
		}else{
			
			String 	torrent_dir 	= pc.getUnsafeStringParameter( "General_sDefaultTorrent_Directory" );

			boolean torrent_bad;
			
			if ( torrent_dir == null || torrent_dir.length() == 0 ){
				
				torrent_bad = true;
				
			}else{
				
				File dir = new File( torrent_dir );
				
				if ( !dir.exists()){
					
					dir.mkdirs();
				}
				
				torrent_bad = !dir.canWrite();
			}
				
			if ( torrent_bad ){		
				
				Logger.log(
					new LogAlert(
						true,
						LogAlert.AT_ERROR,
						MessageText.getString( "xmwebui.error.torrent_path" )));	
			}
		}
	}
	
	@Override
	protected void
	setupServer()
	{
		PluginManager pm = plugin_interface.getPluginManager();
		
		if ( pm.isInitialized()){
			
			super.setupServer();
			
		}else{
			
				// defer the creation of the server as there are a bunch of features of the
				// rpc that require things to be reasonably well initialised to function
				// correctly (e.g. tracker peer sources logic needs other plugins to
				// have initialised)
			
			plugin_interface.addEventListener(
					new PluginEventListener()
					{
						@Override
						public void
						handleEvent(
							PluginEvent 	ev ) 
						{
							if ( ev.getType() == PluginEvent.PEV_ALL_PLUGINS_INITIALISED ){
								
								plugin_interface.removeEventListener( this );

								XMWebUIPlugin.super.setupServer();
							}
						}
					});
		}
	}
	
	@Override
	public void
	unload() 
		
		throws PluginException 
	{
		if ( search_timer != null ){
			
			search_timer.cancel();
			
			search_timer = null;
		}

		com.biglybt.pif.download.DownloadManager dm = plugin_interface.getDownloadManager();
		
		dm.removeDownloadWillBeAddedListener( this );

		dm.removeListener( this );

		if ( json_rpc_client != null ){
		
			plugin_interface.getUtilities().unregisterJSONRPCClient((Utilities.JSONClient)json_rpc_client);
			
			json_rpc_client = null;
		}
		
		json_server_methods.clear();
		
		super.unloadPlugin();
	}
	
	protected void
	setViewMode()
	{
		String mode_str = plugin_interface.getPluginconfig().getPluginStringParameter( WebPlugin.CONFIG_MODE, WebPlugin.CONFIG_MODE_DEFAULT );

		view_mode = !mode_str.equalsIgnoreCase( WebPlugin.CONFIG_MODE_FULL );
		
		checkViewMode();
	}
	   
	public File
	getResourceDir()
	{
		return( new File( plugin_interface.getPluginDirectoryName(), "transmission" + File.separator + "web" ));
	}
	
	@Override
	public void
	downloadAdded(
		Download	download )
	{
	}
	
	// @see com.biglybt.pif.download.DownloadManagerListener#downloadRemoved(com.biglybt.pif.download.Download)
	@Override
	public void
	downloadRemoved(
		Download	download )
	{
		addRecentlyRemoved( download );
	}
	
	private void
	addRecentlyRemoved(
		DownloadStub	download )
	{
		synchronized( recently_removed ){			
			
			long id = getID( download, false );
			
			if ( id > 0 && !stubbifying.contains( id )){
			
				if ( !recently_removed.containsKey( id )){
					
					recently_removed.put( id, new RecentlyRemovedData(id));
				}
			}
		}
	}
	
	boolean
	handleRecentlyRemoved(
		String	session_id,
		Map		args,
		Map		result )
	{
		Object	ids = args.get( "ids" );
		
		if ((ids instanceof String) && ids.equals("recently-active")){
						
			synchronized( recently_removed ){
				
				if ( recently_removed.size() > 0 ){
					
					long now = SystemTime.getMonotonousTime();

					Iterator<RecentlyRemovedData> it = recently_removed.values().iterator();
					
					List<Long>	removed = new ArrayList<>();
					
					while( it.hasNext()){
					
						RecentlyRemovedData rrd = it.next();
							
						if ( !rrd.hasSession( session_id )){
							
							removed.add( rrd.getID());
						}
						
						if ( now - rrd.getCreateTime() > 60*1000 ){
							
							it.remove();
						}
					}
					
					if ( removed.size() > 0 ){

						//System.out.println( "Reporting removed to " + session_id + ": " + removed );
						
						result.put( "removed", removed );
					}
				}
			}
			
			return( true );
			
		}else{
			
			return( false );
		}
	}
	
	

	@Override
	public boolean
	generateSupport(
		TrackerWebPageRequest		request,
		TrackerWebPageResponse		response )
	
		throws IOException
	{
		boolean logit = trace_param.getValue();

		if (request.getInputStream().available() == 0 && "chunked".equals(request.getHeaders().get("transfer-encoding"))) {
			response.setReplyStatus( 415 );
			return true;
		}

		try{
			String session_id = getSessionID( request );
			String	url = request.getURL();

			// Set cookie just in case client is looking for one..
			response.setHeader( "Set-Cookie", "X-Transmission-Session-Id=" + session_id + "; path=/; HttpOnly" );
			// This is the actual spec for massing session-id
			response.setHeader("X-Transmission-Session-Id", session_id );

			if (!isSessionValid(request, true) && !url.startsWith( "/transmission/web")) {
				log("Header:\n" + request.getHeader());
				LineNumberReader lnr;
				if ("gzip".equals(request.getHeaders().get("content-encoding"))) {
					GZIPInputStream gzipIS = new GZIPInputStream(request.getInputStream());
					lnr = new LineNumberReader( new InputStreamReader( gzipIS, "UTF-8" ));
				} else {
					lnr = new LineNumberReader( new InputStreamReader( request.getInputStream(), "UTF-8" ));
				}
				while( true ){
					String	line = lnr.readLine();
					if ( line == null ){
						break;
					}
					log("409: " + line);
				}
				lnr.close();
				response.setReplyStatus( 409 );
				response.getOutputStream().write("You_didn_t_set_the_X-Transmission-Session-Id".getBytes());
				return true;
			}
			
			String session_id_plus = session_id;

			// XXX getHeaders() keys are lowercase.. this line always null?
			String tid = (String)request.getHeaders().get( "X-XMRPC-Tunnel-ID" );
			
			if ( tid != null ){
				
				session_id_plus += "/" + tid;
			}
			
			//System.out.println( "Header: " + request.getHeader() );
			
			// "/rpc" added because some webuis assume they are at /transmission/web/index.html
			//  and use a relative url of "../rpc" to get to /transmission/rpc.
			// We publish to root (so, "/index.html"), or ../rpc results in /rpc
			if ( url.equals( "/transmission/rpc" ) || url.equals("/rpc")){
				
				LineNumberReader lnr;
				if ("gzip".equals(request.getHeaders().get("content-encoding"))) {
					GZIPInputStream gzipIS = new GZIPInputStream(request.getInputStream());
					lnr = new LineNumberReader( new InputStreamReader( gzipIS, "UTF-8" ));
				} else {
					lnr = new LineNumberReader( new InputStreamReader( request.getInputStream(), "UTF-8" ));
				}
					
				StringBuilder request_json_str = new StringBuilder(2048);
				
				while( true ){
					
					String	line = lnr.readLine();
					
					if ( line == null ){
						
						break;
					}
					
					request_json_str.append( line );
				}
				
				if ( logit ){
				
					log( "-> " + request_json_str );
				}
				
				lnr.close();

				if (request_json_str.length() == 0 && !isSessionValid(request, false)) {
					// Some clients call /transmission/rpc with no params to get the X-Transmission-Session-Id
					response.setReplyStatus( 409 );
					return true;
				}

				Map request_json = JSONUtils.decodeJSON( request_json_str.toString());

				Map response_json = processRequest( request, session_id_plus, request_json );

				String response_json_str = JSONUtils.encodeToJSON( response_json );
				
				if ( logit ){
				
					log( "<- " + response_json_str.length() );
					log( "<- " + response_json_str );
				}

				PrintWriter pw =new PrintWriter( new OutputStreamWriter( response.getOutputStream(), "UTF-8" ));
			
				pw.println( response_json_str );
				
				pw.flush();

				response.setContentType( "application/json; charset=UTF-8" );
				
				response.setGZIP( true );
				
				return( true );
			
			}else if ( 	url.startsWith( "/transmission/rpc?json=" ) ||
						url.startsWith( "/vuze/rpc?json=" )){
											
				StringBuffer	request_json_str = new StringBuffer(2048);
				
				request_json_str.append( UrlUtils.decode( url.substring( url.indexOf('?') + 6 )));
							
				if ( logit ){
				
					log( "-> " + request_json_str );
				}
				
				Map response_json;
				Object object = JSONValue.parse(request_json_str.toString());
				if (object instanceof Map) {
					Map request_json = JSONUtils.decodeJSON( request_json_str.toString());
					response_json = processRequest( request, session_id_plus, request_json );
				} else {
					response_json = new HashMap();
					response_json.put( "result", "error: Bad or missing JSON string");
					response_json.put("request", request_json_str);
				}

				
				String response_json_str = JSONUtils.encodeToJSON( response_json );
				
				if ( logit ){
				
					log( "<- " + response_json_str );
				}
				
				PrintWriter pw =new PrintWriter( new OutputStreamWriter( response.getOutputStream(), "UTF-8" ));
			
				pw.println( response_json_str );
				
				pw.flush();
				
				response.setContentType( "application/json; charset=UTF-8" );
				
				response.setGZIP( true );
				
				return( true );
				
				/* example code to relay a stream
			}else if ( url.startsWith( "/vuze/test.mkv" )){
				
				Map headers = request.getHeaders();
								
				OutputStream os = response.getRawOutputStream();
				
				Socket sock = new Socket( "127.0.0.1", 46409 );
				
				OutputStream sos = sock.getOutputStream();
				
				String req = "GET /Content/test.mkv HTTP/1.1\r\n";
				
				String range = (String)headers.get( "range" );
				
				if ( range != null ){
					
					req += "Range: " + range + "\r\n";
				}
				
				req += "\r\n";
				
				sos.write( req.getBytes( "ISO-8859-1"));
				
				sos.flush();
				
				InputStream is = sock.getInputStream();
				
				byte[]	buffer = new byte[256*1024];
				
				while( true ){
				
					int	len = is.read( buffer );
					
					if ( len <= 0 ){
						
						break;
					}
					
					os.write( buffer, 0, len );
				}
				
				return( true );
				*/
				
			}else if ( url.startsWith( "/vuze/resource?json=" )){

				Map request_json = JSONUtils.decodeJSON( UrlUtils.decode( url.substring( url.indexOf( '?' ) + 6 )));
				
				return( processResourceRequest( request, response, request_json ));
				
			}else if ( url.startsWith( "/transmission/upload" )){
					
				if ( logit ){
					
					log( "upload request" );
				}
				
				checkUpdatePermissions();
				
				boolean	add_stopped = url.endsWith( "paused=true" );
				
				String	content_type = (String)request.getHeaders().get( "content-type" );
				
				if ( content_type == null ){
					
					throw( new IOException( "Content-Type missing" ));
				}
				
				int bp = content_type.toLowerCase().indexOf( "boundary=" );
				
				if ( bp == -1 ){
					
					throw( new IOException( "boundary missing" ));
				}
				
				String	boundary = content_type.substring(bp+9).trim();
				
				int	ep = boundary.indexOf( ';' );
				
				if ( ep != -1 ){
					
					boundary = boundary.substring(0,ep).trim();
				}
				
				MultiPartDecoder.FormField[] fields = new MultiPartDecoder().decode(boundary, request.getInputStream());
				
				try{
					
					int	num_found = 0;
					
					for ( MultiPartDecoder.FormField field: fields ){
						
						String field_name = field.getName();
						
						if ( field_name.equalsIgnoreCase( "torrent_file" ) || field_name.equalsIgnoreCase( "torrent_files[]" )){
					
							num_found++;
							
							String torrent_file_name = field.getAttribute( "filename" );
							
							if ( torrent_file_name == null ){
								
								throw( new IOException( "upload filename missing" ));
							}
							
							InputStream tis = field.getInputStream();
							
							Torrent torrent;
							
							try{
								torrent = plugin_interface.getTorrentManager().createFromBEncodedInputStream( tis );
							
								torrent.setDefaultEncoding();
								
							}catch( Throwable e ){
								
								throw( new IOException( "Failed to deserialise torrent file: " + StaticUtils.getCausesMesssages(e)));
							}
							
							try{
								Download download = addTorrent( torrent, null, add_stopped, null );
								
								response.setContentType( "text/xml; charset=UTF-8" );
								
								response.getOutputStream().write( "<h1>200: OK</h1>".getBytes());
								
								return( true );
								
							}catch( Throwable e ){
								
								throw( new IOException( "Failed to add torrent: " + StaticUtils.getCausesMesssages(e)));
	
							}
						}
					}
					
					if ( num_found == 0 ){
						
						log( "No torrents found in upload request" );
					}
					
					return( true );
					
				}finally{
					
					for ( MultiPartDecoder.FormField field: fields ){
						
						field.destroy();
					}
				}
			}else if ( url.startsWith( "/transmission/web")){

				String webdir = webdir_param.getValue();
				if (webdir == null || webdir.isEmpty()) {

					response.setReplyStatus( 301 );

					response.setHeader( "Location", "/" );
				} else {
					String reldir = url.substring("/transmission/web".length());
					if (reldir.isEmpty()) {
						response.setReplyStatus( 301 );

						response.setHeader( "Location", "/transmission/web/" );
					} else if (reldir.endsWith("/")) {
						reldir += "index.html";
					}
					response.useFile(webdir, reldir);
				}
					
				return( true );
				
			}else if ( url.startsWith( SEARCH_PREFIX )){
				
				return( search_handler.generate( request, response ));
			
			}else{
			
				return( false );
			}
		}catch( PermissionDeniedException e ){
			
			response.setReplyStatus( 401 );

			return( true );
			
		}catch( IOException e ){
			
			if ( logit ){
			
				log( "Processing failed", e );
				e.printStackTrace();
			}
			
			throw( e );
			
		}catch( Throwable e ){
			
			if ( logit ){
				
				log( "Processing failed", e );
				e.printStackTrace();
			}
			
			throw( new IOException( "Processing failed: " + StaticUtils.getCausesMesssages( e )));
		}
	}
	
	private static String
	getCookie(
		String		cookies,
		String		cookie_id)
	{
		if ( cookies == null ){
			
			return null;
		}

		List<String> cookie_list = StaticUtils.fastSplit(cookies, ';');
		
		for ( String cookie: cookie_list ){

			List<String> bits = StaticUtils.fastSplit(cookie, '=');
			
			if ( bits.size() == 2 ){
				
				if ( bits.get(0).trim().equals( cookie_id )){
					
					return bits.get(1).trim();
				}
			}
		}
		
		return null;
	}

	private boolean 
	isSessionValid(
			TrackerWebPageRequest request,
			boolean checkCookie) 
 {
		if (!request.getURL().startsWith("/transmission/")) {
			return true;
		}

		Map headers = request.getHeaders();
		
			// tunnel requests are already strongly authenticated and session based
		String tunnel = (String)headers.get( "x-vuze-is-tunnel" );
		
		if ( tunnel != null && tunnel.equalsIgnoreCase( "true" )){
			return true;
		}
		String session_id = getSessionID(request);
		String header_session_id = (String) headers.get(
				"X-Transmission-Session-Id");

		if (header_session_id == null) {
			header_session_id = (String) headers.get(
					"x-transmission-session-id");
		}
		if (header_session_id == null && checkCookie) {
			header_session_id = getCookie(
					(String) headers.get("cookie"),
					"X-Transmission-Session-Id");
		}

		//System.out.println("header_session_id=" + header_session_id);

		if (header_session_id == null) {
			return false;
		}

		return (header_session_id.equals(session_id));
	}

	String 
	getSessionID(
			TrackerWebPageRequest request) 
	{
		String clientAddress = request.getClientAddress();
		
		synchronized (ip_to_session_id) {
			String session_id = ip_to_session_id.get(clientAddress);
			if (session_id == null) {
				session_id = Double.toHexString(Math.random());
				ip_to_session_id.put(clientAddress, session_id);
			}
			
			return session_id;
		}
	}

	private final static Object add_torrent_lock = new Object();
	
	private ByteArrayHashMap<DownloadWillBeAddedListener>	add_torrent_listeners = new ByteArrayHashMap<>();
	
	@Override
	public void initialised(Download dlAdding) {
		
		DownloadWillBeAddedListener listener;
		
		synchronized( add_torrent_lock ){
			
			listener = add_torrent_listeners.remove( dlAdding.getTorrent().getHash());
		}
		
		if ( listener != null ){
			
			listener.initialised(dlAdding);
		}
	}
	
	protected Download
	addTorrent(
		Torrent						torrent,
		File 						download_dir,
		boolean						add_stopped,
		DownloadWillBeAddedListener listener)
	
		throws DownloadException
	{
		synchronized( add_torrent_lock ){

			com.biglybt.pif.download.DownloadManager dm = plugin_interface.getDownloadManager();			
			
			Download download = dm.getDownload( torrent );
			
			if ( download == null ){

				DownloadWillBeAddedListener my_listener = 
					new DownloadWillBeAddedListener()
					{
						@Override
						public void 
						initialised(
							Download download)
						{
							if ( listener != null ){
								
								listener.initialised(download);
							}
							
							if ( force_net_param.getValue()){
								
								TorrentAttribute ta = plugin_interface.getTorrentManager().getAttribute( TorrentAttribute.TA_NETWORKS );
									
								download.setListAttribute( ta, AENetworkClassifier.getDefaultNetworks());
							}
						}
					};
					
				add_torrent_listeners.put( torrent.getHash(), my_listener );

				if ( add_stopped ){
					
					download = dm.addDownloadStopped( torrent, null, download_dir );
					
				}else{
					
					download = dm.addDownload( torrent, null, download_dir );
				}
			
			
					// particularly necessary for the android client as untidy closedown is common
			
				CoreFactory.getSingleton().saveState();
			}
			
			return( download );
		}
	}
	
	protected void
	checkUpdatePermissions()
		
		throws IOException
	{
		if ( view_mode ){
			
			log( "permission denied" );
			
			throw(new PermissionDeniedException());
		}
	}
	
	protected Map
	processRequest(
		TrackerWebPageRequest		wp_request,
		String						session_id,
		Map							request )
	
		throws IOException
	{
		Map	response = new HashMap();

		if (request == null) {
			response.put( "result", "error: Bad or missing JSON string");
			return response;
		}

		String method = (String)request.get( "method" );
		
		if ( method == null ){
			
			throw( new IOException( "'method' missing" ));
		}

		Map	args = (Map)request.get( "arguments" );
		
		if ( args == null ){
			
			args = new HashMap();
		}

		try{
			Map	result = processRequest( wp_request, session_id, method, args );

			if ( result == null ){
				
				result = new HashMap();
			}
				
			response.put( "arguments", result );
			
			response.put( "result", "success" );
			
		}catch( PermissionDeniedException e ){
			
			throw( e );
			
		}catch( TextualException e ){

			response.put( "result", e.getMessage());
		
		}catch( Throwable e ){
			log("processRequest", e);
			response.put( "result", "error: " + StaticUtils.getCausesMesssages( e ));
		}
		
		Object	tag = request.get( "tag" );

		if ( tag != null ){
			
			response.put( "tag", tag );
		}
		
		return( response );
	}

	protected Map
	processRequest(
		TrackerWebPageRequest		request,
		String						session_id,
		String						method,
		Map							args )
	
		throws Exception
	{
		boolean	save_core_state = false;
		
		try{
			Map	result = new HashMap();
					
				// https://trac.transmissionbt.com/browser/trunk/extras/rpc-spec.txt
			
				// to get 271 working with this backend change remote.js RPC _Root to be
				// _Root                   : './transmission/rpc',

			switch (method) {
				case METHOD_SESSION_SET:

					try {
						SessionMethods.method_Session_Set(this, plugin_interface, args);

					} finally {

						// assume something important was changed and persist it now 

						COConfigurationManager.save();
					}

					break;
				case METHOD_SESSION_GET:

					SessionMethods.method_Session_Get(this, plugin_interface, request, args,
							result);

					break;
				case METHOD_SESSION_STATS:

					SessionMethods.method_Session_Stats(args, result);

					break;
				case "torrent-add":
					String agent = MapUtils.getMapString(request.getHeaders(), "User-Agent", "");
					boolean xmlEscape = agent.startsWith("Mozilla/");

					method_Torrent_Add(args, result, xmlEscape);

					// this is handled within the torrent-add method: save_core_state = true;

					break;
				case "torrent-start-all":

					checkUpdatePermissions();

					plugin_interface.getDownloadManager().startAllDownloads();

					save_core_state = true;

					break;
				case "torrent-stop-all":

					checkUpdatePermissions();

					plugin_interface.getDownloadManager().stopAllDownloads();

					save_core_state = true;

					break;
				case "torrent-start":

					method_Torrent_Start(args, result);

					save_core_state = true;

					break;
				case "torrent-start-now":
					// RPC v14

					method_Torrent_Start_Now(args, result);

					save_core_state = true;

					break;
				case "torrent-stop":

					method_Torrent_Stop(args, result);

					save_core_state = true;

					break;
				case "torrent-verify":

					method_Torrent_Verify(args, result);

					break;
				case METHOD_TORRENT_REMOVE:
					// RPC v3

					method_Torrent_Remove(args, result);

					save_core_state = true;

					break;
				case METHOD_TORRENT_SET:

					method_Torrent_Set(session_id, args, result);

					break;
				case METHOD_TORRENT_GET:

					TorrentGetMethods.method_Torrent_Get(this, request, session_id, args, result);

					break;
				case METHOD_TORRENT_REANNOUNCE:
					// RPC v5

					method_Torrent_Reannounce(args, result);

					break;
				case METHOD_TORRENT_SET_LOCATION:
					// RPC v6

					method_Torrent_Set_Location(args, result);

					break;
				case "blocklist-update":
					// RPC v5

					method_Blocklist_Update(args, result);

					break;
				case "session-close":
					// RPC v12
					//TODO: This method tells the transmission session to shut down.

					break;
				case METHOD_Q_MOVE_TOP:
					// RPC v14
					method_Queue_Move_Top(args, result);

					break;
				case "queue-move-up":
					// RPC v14
					method_Queue_Move_Up(args, result);

					break;
				case "queue-move-down":
					// RPC v14
					method_Queue_Move_Down(args, result);

					break;
				case METHOD_Q_MOVE_BOTTOM:
					// RPC v14
					method_Queue_Move_Bottom(args, result);

					break;
				case METHOD_FREE_SPACE:
					// RPC v15
					method_Free_Space(args, result);

					break;
				case "torrent-rename-path":
					// RPC v15
					method_Torrent_Rename_Path(args, result);

					break;
				case "tags-get-list":
					// Vuze RPC v3
					method_Tags_Get_List(args, result);

					break;
				case METHOD_TAGS_LOOKUP_START:

					method_Tags_Lookup_Start(args, result);

					break;
				case METHOD_TAGS_LOOKUP_GET_RESULTS:

					method_Tags_Lookup_Get_Results(args, result);

					break;
				case METHOD_SUBSCRIPTION_GET:

					method_Subscription_Get(args, result);

					break;
				case "subscription-add":

					method_Subscription_Add(args, result);

					break;
				case METHOD_SUBSCRIPTION_SET:

					method_Subscription_Set(args, result);

					break;
				case METHOD_SUBSCRIPTION_REMOVE:

					method_Subscription_Remove(args, result);

					break;
				case "vuze-search-start":

					method_Vuze_Search_Start(args, result);

					break;
				case METHOD_VUZE_SEARCH_GET_RESULTS:

					method_Vuze_Search_Get_Results(args, result);

					break;
				case "vuze-config-set":

					method_Vuze_Config_Set(args, result);

					break;
				case "vuze-config-get":

					method_Vuze_Config_Get(args, result);

					break;
				case "vuze-plugin-get-list":

					method_Vuze_Plugin_Get_List(args, result);

					break;
				case "vuze-lifecycle":

					processVuzeLifecycle(args, result);

					break;
				case "vuze-pairing":

					processVuzePairing(args, result);

					break;
				case "vuze-torrent-get":

					processVuzeTorrentGet(request, args, result);

					break;
				case "vuze-file-add":

					processVuzeFileAdd(args, result);

					break;
				case "bigly-console":

					processConsole(args, result);

					break;
				default:

					JSONServer server = (JSONServer) json_server_methods.get(method);

					if (server != null) {

						return (server.call(method, args));
					}

					if (trace_param.getValue()) {
						log("unhandled method: " + method + " - " + args);
					}
					break;
			}
	
			return( result );
			
		}finally{
			
			if ( save_core_state ){
				
					// particularly necessary for the android client as untidy closedown is common
				
				CoreFactory.getSingleton().saveState();
			}
		}
	}
	
	private static void method_Vuze_Plugin_Get_List(Map args, Map result) {
		String sep = System.getProperty("file.separator");

		File fUserPluginDir = FileUtil.getUserFile("plugins");
		String sUserPluginDir;
		try {
			sUserPluginDir = fUserPluginDir.getCanonicalPath();
		} catch (Throwable e) {
			sUserPluginDir = fUserPluginDir.toString();
		}
		if (!sUserPluginDir.endsWith(sep)) {
			sUserPluginDir += sep;
		}

		File fAppPluginDir = FileUtil.getApplicationFile("plugins");
		String sAppPluginDir;
		try {
			sAppPluginDir = fAppPluginDir.getCanonicalPath();
		} catch (Throwable e) {
			sAppPluginDir = fAppPluginDir.toString();
		}
		if (!sAppPluginDir.endsWith(sep)) {
			sAppPluginDir += sep;
		}

		PluginInterface[] pluginIFs = CoreFactory.getSingleton().getPluginManager().getPlugins();
		for (PluginInterface pi : pluginIFs) {

			Map mapPlugin = new HashMap();
			result.put(pi.getPluginID(), mapPlugin);
			mapPlugin.put("name", pi.getPluginName());

			String sDirName = pi.getPluginDirectoryName();
			String sKey;

			if (sDirName.length() > sUserPluginDir.length()
					&& sDirName.substring(0, sUserPluginDir.length()).equals(
							sUserPluginDir)) {
				sKey = "perUser";

			} else if (sDirName.length() > sAppPluginDir.length()
					&& sDirName.substring(0, sAppPluginDir.length()).equals(
							sAppPluginDir)) {
				sKey = "shared";
			} else {
				sKey = "builtIn";
			}

			mapPlugin.put("type", sKey);
			mapPlugin.put("version", pi.getPluginVersion());
			PluginState pluginState = pi.getPluginState();
			mapPlugin.put("isBuiltIn", pluginState.isBuiltIn());
			mapPlugin.put("isDisabled", pluginState.isDisabled());
			mapPlugin.put("isInitialisationComplete",
					pluginState.isInitialisationComplete());
			mapPlugin.put("isLoadedAtStartup", pluginState.isLoadedAtStartup());
			mapPlugin.put("isMandatory", pluginState.isMandatory());
			mapPlugin.put("isOperational", pluginState.isOperational());
			mapPlugin.put("isShared", pluginState.isShared());
			mapPlugin.put("isUnloadable", pluginState.isUnloadable());
			mapPlugin.put("isUnloaded", pluginState.isUnloaded());
		}
	}

	private static void method_Vuze_Config_Get(Map args, Map result) {
		List listKeys = MapUtils.getMapList(args, "keys", Collections.EMPTY_LIST);
		for (Object key : listKeys) {
			String keyString = key.toString();
			if (ignoreConfigKey(keyString)) {
				continue;
			}
			Object val = COConfigurationManager.getParameter(keyString);
			if (val instanceof byte[]) {
				// Place parsed string in key's value, B64 of bytes in key + ".B64"
				String valString;
				byte[] bytes = (byte[]) val;
				try {
					valString = new String(bytes, Constants.DEFAULT_ENCODING);
				} catch (Throwable e) {
					valString = new String(bytes);
				}

				result.put(key, valString);
				try {
					result.put(key + ".B64", new String(Base64.encode(bytes), "utf8"));
				} catch (UnsupportedEncodingException e) {
				}
			} else {
				result.put(key, val);
			}
		}

	}

	private static void method_Vuze_Config_Set(Map args, Map result) {
		Map mapDirect = MapUtils.getMapMap(args, "direct", Collections.EMPTY_MAP);
		for (Object key : mapDirect.keySet()) {
			String keyString = key.toString();
			if (ignoreConfigKey(keyString)) {
				result.put(keyString, "key ignored");
				continue;
			}
			Object val = mapDirect.get(key);
			boolean changed;
			if (val instanceof String) {
				changed = COConfigurationManager.setParameter(keyString, (String) val);
			} else if (val instanceof Boolean) {
				changed = COConfigurationManager.setParameter(keyString, (Boolean) val);
			} else if (val instanceof Float) {
				changed = COConfigurationManager.setParameter(keyString, (Float) val);
			} else if (val instanceof Double) {
				changed = COConfigurationManager.setParameter(keyString,
						((Number) val).floatValue());
			} else if (val instanceof Number) {
				changed = COConfigurationManager.setParameter(keyString,
						((Number) val).longValue());
			} else if (val instanceof Map) {
				changed = COConfigurationManager.setParameter(keyString, (Map) val);
			} else {
				result.put(keyString, "error");
				continue;
			}
			result.put(keyString, changed);
		}

		Map mapByteArray = MapUtils.getMapMap(args, "byteArray.B64",
				Collections.EMPTY_MAP);
		for (Object key : mapByteArray.keySet()) {
			String keyString = key.toString();
			if (ignoreConfigKey(keyString)) {
				result.put(keyString, "key ignored");
				continue;
			}
			Object val = mapByteArray.get(key);
			if (val instanceof String) {
				byte[] decode = Base64.decode((String) val);
				boolean changed = COConfigurationManager.setParameter(keyString,
						decode);
				result.put(keyString, changed);
			} else {
				result.put(keyString, "error");
			}
		}

		COConfigurationManager.save();
	}
	
	private static boolean
	ignoreConfigKey(
		String		key )
	{
		String lc_key = key.toLowerCase(Locale.US);

		if (key.startsWith(CryptoManager.CRYPTO_CONFIG_PREFIX)
				|| lc_key.equals("id") || lc_key.equals("azbuddy.dchat.optsmap")
				|| lc_key.endsWith(".privx") || lc_key.endsWith(".user")
				|| lc_key.contains("password") || lc_key.contains("username")
				|| lc_key.contains("session key")) {

			return (true);
		}

		Object value = COConfigurationManager.getParameter(key);

		if (value instanceof byte[]) {

			try {
				value = new String((byte[]) value, "UTF-8");

			} catch (Throwable e) {

			}
		}

		if (value instanceof String) {

			if (((String) value).toLowerCase(Locale.US).endsWith(".b32.i2p")) {

				return (true);
			}
		}

		return (false);
	}


	private void method_Tags_Lookup_Start(Map args, Map result) {
		Object ids = args.get("ids");
		
		TagSearchInstance tagSearchInstance = new TagSearchInstance();

		try {
			List<String> listDefaultNetworks = new ArrayList<>();
			for (int i = 0; i < AENetworkClassifier.AT_NETWORKS.length; i++) {

				String nn = AENetworkClassifier.AT_NETWORKS[i];

				String config_name = "Network Selection Default." + nn;
				boolean enabled = COConfigurationManager.getBooleanParameter(
						config_name, false);
				if (enabled) {
					listDefaultNetworks.add(nn);
				}
			}

			com.biglybt.pif.download.DownloadManager dlm = plugin_interface.getDownloadManager();
			String[] networks;
			if (ids instanceof List) {
				List idList = (List) ids;
				for (Object id : idList) {
					if (id instanceof String) {
						String hash = (String) id;
						byte[] hashBytes = ByteFormatter.decodeString(hash);
						Download download = dlm.getDownload(hashBytes);
						
						DownloadManager dm = PluginCoreUtils.unwrap(download);
						if (dm != null) {
							networks = dm.getDownloadState().getNetworks();
						} else {
							networks = listDefaultNetworks.toArray(new String[0]);
						}
						
						tagSearchInstance.addSearch(hash, hashBytes, networks);
						synchronized (active_tagsearches) {
							active_tagsearches.put(tagSearchInstance.getID(), tagSearchInstance);
						}
					}
				}

			}
		} catch (Throwable t) {

		}

		result.put("id", tagSearchInstance.getID());
	}

	private void method_Tags_Lookup_Get_Results(Map args, Map result)
			throws IOException {
		String id = (String) args.get("id");

		if (id == null) {
			throw (new IOException("ID missing"));
		}

		synchronized (active_tagsearches) {
			TagSearchInstance search_instance = active_tagsearches.get(id);

			if (search_instance != null) {
				if (search_instance.getResults(result)) {
					active_tagsearches.remove(id);
				}
			} else {
				throw (new IOException("ID not found - already complete?"));
			}
		}
	}


	private void method_Vuze_Search_Get_Results(Map args, Map result)
			throws IOException {
		String sid = (String) args.get("sid");

		if (sid == null) {
			throw (new IOException("SID missing"));
		}

		synchronized (active_searches) {
			SearchInstance search_instance = active_searches.get(sid);

			if (search_instance != null) {
				if (search_instance.getResults(result)) {
					active_searches.remove(sid);
				}
			} else {
				throw (new IOException("SID not found - already complete?"));
			}
		}
	}

	private void method_Vuze_Search_Start(Map args, Map result)
			throws IOException {
		String expression = (String) args.get("expression");
		
		if (expression == null) {
			throw (new IOException("Search expression missing"));
		}

		MetaSearchManager ms_manager = MetaSearchManagerFactory.getSingleton();
		MetaSearch ms = ms_manager.getMetaSearch();
		List<SearchParameter> sps = new ArrayList<>();

		sps.add(new SearchParameter("s", expression));

		SearchParameter[] parameters = sps.toArray(new SearchParameter[0]);

		Map<String, String> context = new HashMap();
		context.put(Engine.SC_SOURCE, "xmwebui");
		context.put(Engine.SC_REMOVE_DUP_HASH, "true");

		Engine[] engines = ms_manager.getMetaSearch().getEngines(true, true);

		if (engines.length == 0) {
			throw (new IOException("No search templates available"));
		}

		SearchInstance search_instance = new SearchInstance(this, engines);

		engines = ms.search(engines, search_instance, parameters, null, context,
				100);

		if (engines.length == 0) {
			throw (new IOException("No search templates available"));
		}

		synchronized (active_searches) {
			active_searches.put(search_instance.getSID(), search_instance);
		}

		search_instance.setEngines(engines);

		result.put("sid", search_instance.getSID());

		List<Map> l_engines = new ArrayList<>();
		result.put("engines", l_engines);

		for (Engine engine : engines) {
			JSONObject map = new JSONObject();

			l_engines.add(map);

			map.put("name", engine.getName());
			map.put("id", engine.getUID());
			map.put("favicon", engine.getIcon());
			map.put("dl_link_css", engine.getDownloadLinkCSS());
			map.put("selected", Engine.SEL_STATE_STRINGS[engine.getSelectionState()]);
			map.put("source", Engine.ENGINE_SOURCE_STRS[engine.getSource()]);
			int type = engine.getType();
			map.put("type", type < Engine.ENGINE_TYPE_STRS.length ? Engine.ENGINE_TYPE_STRS[type] : type);
		}
	}

	private static void method_Subscription_Add(Map args, Map result) throws MalformedURLException, SubscriptionException {
		String url = MapUtils.getMapString(args, "rss-url", null);
		String name = MapUtils.getMapString(args, FIELD_SUBSCRIPTION_NAME,
				"Subscription " + DateFormat.getInstance().toString());
		boolean anonymous = MapUtils.getMapBoolean(args, "anonymous", false);
		

		if (url != null) {
			Subscription subRSS = SubscriptionManagerFactory.getSingleton().createRSS(
					name, new URL(url), SubscriptionHistory.DEFAULT_CHECK_INTERVAL_MINS, anonymous,
					null);
			
			result.put("subscription", subRSS.getJSON());
		}
	}

	
	/*
	 * {
	 *   <Subscription List ID> :
	 *   {
	 *     <field> : value,
	 *     "results" : {
	 *       <field> : value,
	 *       etc
	 *     },
	 *     etc
	 *   },
	 *   etc
	 * }
	 */
	private static void method_Subscription_Set(Map args, Map result)
			throws SubscriptionException, IOException {
		Object oIDs = args.get("ids");

		if (oIDs == null) {
			throw new IOException("ids missing");
		}

		if (!(oIDs instanceof Map)) {
			throw new IOException("ids not map");
		}

		Map mapSubscriptionIDs = (Map) oIDs;
		SubscriptionManager subMan = SubscriptionManagerFactory.getSingleton();
		for (Object oSubscriptionID : mapSubscriptionIDs.keySet()) {
			Subscription subs = subMan.getSubscriptionByID((String) oSubscriptionID);
			if (subs == null) {
				result.put(oSubscriptionID, "Error: Not Found");
				continue;
			}
			Object oVal = mapSubscriptionIDs.get(oSubscriptionID);
			if (!(oVal instanceof Map)) {
				continue;
			}
			Map mapSubscriptionFields = (Map) oVal;
			// could change name, subscribed state, etc

			int numChanged = 0;

			for (Object oSubscriptionFieldName : mapSubscriptionFields.keySet()) {
				String subscriptionFieldName = (String) oSubscriptionFieldName;
				Object oSubscriptionFieldValue = mapSubscriptionFields.get(
						subscriptionFieldName);
				if (subscriptionFieldName.equals(FIELD_SUBSCRIPTION_NAME)) {
					subs.setName((String) oSubscriptionFieldValue);
					numChanged++;

				} else if (subscriptionFieldName.equals(FIELD_SUBSCRIPTION_AUTO_DOWNLOAD)
						&& (oSubscriptionFieldValue instanceof Boolean)) {
					subs.getHistory().setAutoDownload((Boolean) oSubscriptionFieldValue);
					numChanged++;
				
				} else if (subscriptionFieldName.equals(FIELD_SUBSCRIPTION_SUBSCRIBED)
						&& (oSubscriptionFieldValue instanceof Boolean)) {
					subs.setSubscribed((Boolean) oSubscriptionFieldValue);
					numChanged++;
				
				} else if (subscriptionFieldName.equals(FIELD_SUBSCRIPTION_RESULTS)
						&& (oSubscriptionFieldValue instanceof Map)) {
					
					Map mapResults = (Map) oSubscriptionFieldValue;
					SubscriptionResult[] results = subs.getResults(false);
					for (Object oResultKey : mapResults.keySet()) {
						String subs_id = (String) oResultKey;
						Map mapResultEntries = (Map) mapResults.get(oResultKey);

						for (SubscriptionResult entry : results) {
							if (entry.getID().equals(subs_id)) {
								Boolean isRead = (Boolean) mapResultEntries.get(
										FIELD_SUBSCRIPTION_RESULT_ISREAD);
								if (isRead != null) {
									numChanged++;
									entry.setRead(isRead);
								}
								break;
							}
						}
					}
				}
				
				if (numChanged > 0) {
					Map<String, Object> map = buildSubscriptionMap(subs, null, null, true);
					result.put(oSubscriptionID, map);
				}
			}

		}
	}

	private static void method_Subscription_Remove(Map args, Map result) throws IOException {
		Object oID = args.get("ids");

		if (oID == null) {
			throw new IOException("ID missing");
		}
		
		String[] ids = new String[0];
		if (oID instanceof String) {
			ids = new String[] { (String) oID };
		} else if (oID instanceof Collection) {
			ids = (String[]) ((Collection) oID).toArray(new String[0]);
		} else if (oID instanceof Object[]) {
			Object[] oIDS = (Object[]) oID; 
			ids = new String[oIDS.length];
			for (int i = 0; i < oIDS.length; i++) {
				ids[i] = oIDS[i].toString();
			}
		}
		
		SubscriptionManager subMan = SubscriptionManagerFactory.getSingleton();
		for (String id : ids) {
			Subscription subs = subMan.getSubscriptionByID(id);
			if (subs == null) {
				result.put(id, "Error: Not Found");
			} else {
				subs.remove();
				result.put(id, "Removed");
			}
		}
	}

	
	
	/*
	 * For non-torrent specific:
	 * 
	 * Subscriptions : 
	 * {
	 *   SubscriptionID : 
	 *   {
	 *   	Field:Value,
	 *   },
	 *   SubscriptionID : 
	 *   {
	 *   	Field:Value,
	 *   },
	 * }
	 * 
	 * For torrent specific:
	 * Subscriptions :
	 * {
	 *   SubscriptionID: {
	 *     torrentId: #,
	 * 	   Field:Value,
	 *   },
	 *   etc
	 * }
	 */
	private void method_Subscription_Get(Map args, Map result)
			throws IOException {

		boolean subscribedOnly = MapUtils.getMapBoolean(args, "subscribed-only",
				true);

		Map<Object, Map<String, Object>> mapSubcriptions = new HashMap<>();

		SubscriptionManager subMan = SubscriptionManagerFactory.getSingleton();

		List fields = (List) args.get(ARG_FIELDS);
		boolean all = fields == null || fields.size() == 0;
		if (!all) {
			// sort so we can't use Collections.binarySearch
			Collections.sort(fields);
		}

		Object oTorrentHashes = args.get("torrent-ids");
		if (oTorrentHashes != null) {
			List<DownloadStub> downloads = getDownloads(oTorrentHashes, false);
			for (DownloadStub stub : downloads) {
				Subscription[] subs = subMan.getKnownSubscriptions(
						stub.getTorrentHash());
				if (subs != null) {
					for (Subscription sub : subs) {
						Map<String, Object> map = buildSubscriptionMap(sub, args, fields, all);
						map.put("torrentID", getID(stub, false));

						mapSubcriptions.put(sub.getID(), map);
					}
				}
			}
		} else {
			Subscription[] subscriptions;

			Object oID = args.get("ids");

			String[] ids = new String[0];
			if (oID instanceof String) {
				ids = new String[] {
					(String) oID
				};
			} else if (oID instanceof Collection) {
				ids = (String[]) ((Collection) oID).toArray(new String[0]);
			} else if (oID instanceof Object[]) {
				Object[] oIDS = (Object[]) oID;
				ids = new String[oIDS.length];
				for (int i = 0; i < oIDS.length; i++) {
					ids[i] = oIDS[i].toString();
				}
			}

			if (ids.length == 0) {
				subscriptions = subMan.getSubscriptions(subscribedOnly);
			} else {
				List<Subscription> list = new ArrayList<>();
				for (String id : ids) {
					Subscription subscriptionByID = subMan.getSubscriptionByID(id);
					if (subscriptionByID == null) {
						mapSubcriptions.put(id, Collections.EMPTY_MAP);
					} else {
						list.add(subscriptionByID);
					}
				}
				subscriptions = list.toArray(new Subscription[0]);
			}

			for (Subscription sub : subscriptions) {
				Map<String, Object> map = buildSubscriptionMap(sub, args, fields, all);

				mapSubcriptions.put(sub.getID(), map);
			}
		}

		result.put(FIELD_SUBSCRIPTION_LIST, mapSubcriptions);
	}



	private static Map<String, Object> buildSubscriptionMap(Subscription sub, Map args,
			List fields, boolean all) {
		Map<String, Object> map = new HashMap<>();
		
		if (all || Collections.binarySearch(fields,
				"json") >= 0) {
			try {
				Map mapJSON = JSONUtils.decodeJSON(sub.getJSON());
				mapJSON.remove("engines");
				map.put("json", mapJSON);
			} catch (SubscriptionException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		}

		if (all
				|| Collections.binarySearch(fields, FIELD_SUBSCRIPTION_NAME) >= 0) {
			map.put(FIELD_SUBSCRIPTION_NAME, sub.getName());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_ADDEDON) >= 0) {
			map.put(FIELD_SUBSCRIPTION_ADDEDON, sub.getAddTime());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_ASSOCIATION_COUNT) >= 0) {
			map.put(FIELD_SUBSCRIPTION_ASSOCIATION_COUNT,
					sub.getAssociationCount());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_POPULARITY) >= 0) {
			map.put(FIELD_SUBSCRIPTION_POPULARITY, sub.getCachedPopularity());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_CATEGORY) >= 0) {
			addNotNullToMap(map, FIELD_SUBSCRIPTION_CATEGORY, sub.getCategory());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_CREATOR) >= 0) {
			addNotNullToMap(map, FIELD_SUBSCRIPTION_CREATOR, sub.getCreatorRef());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_ENGINE) >= 0) {
			try {
				Engine engine = sub.getEngine();
				if (engine != null) {
					Map mapEngine = new HashMap();
					map.put(FIELD_SUBSCRIPTION_ENGINE, mapEngine);

					if (all || Collections.binarySearch(fields,
							FIELD_SUBSCRIPTION_ENGINE_NAME) >= 0) {
						mapEngine.put("name", engine.getName());
					}
					if (all || Collections.binarySearch(fields,
							FIELD_SUBSCRIPTION_ENGINE_NAMEX) >= 0) {
						mapEngine.put(FIELD_SUBSCRIPTION_ENGINE_NAMEX,
								engine.getNameEx());
					}
					if (all || Collections.binarySearch(fields,
							FIELD_SUBSCRIPTION_ENGINE_TYPE) >= 0) {
						map.put(FIELD_SUBSCRIPTION_ENGINE_TYPE, engine.getType());
						int type = engine.getType();
						mapEngine.put("type", type < Engine.ENGINE_TYPE_STRS.length
								? Engine.ENGINE_TYPE_STRS[type] : type);
					}

					if (all || Collections.binarySearch(fields,
							FIELD_SUBSCRIPTION_ENGINE_NAMEX) >= 0) {
						mapEngine.put(FIELD_SUBSCRIPTION_ENGINE_NAMEX,
								engine.getNameEx());
					}
					//engine.getAutoDownloadSupported() same as sub.getAutoDownloadSupported

					if (all || Collections.binarySearch(fields,
							FIELD_SUBSCRIPTION_ENGINE_SOURCE) >= 0) {
						mapEngine.put(FIELD_SUBSCRIPTION_ENGINE_SOURCE,
								Engine.ENGINE_SOURCE_STRS[engine.getSource()]);
					}
					if (all || Collections.binarySearch(fields,
							FIELD_SUBSCRIPTION_ENGINE_LASTUPDATED) >= 0) {
						mapEngine.put(FIELD_SUBSCRIPTION_ENGINE_LASTUPDATED,
								engine.getLastUpdated());
					}

					mapEngine.put("id", engine.getUID());
					addNotNullToMap(mapEngine, FIELD_SUBSCRIPTION_FAVICON, engine.getIcon());
					mapEngine.put("dl_link_css", engine.getDownloadLinkCSS());
					mapEngine.put("selected",
							Engine.SEL_STATE_STRINGS[engine.getSelectionState()]);
					mapEngine.put("class", engine.getClass().getSimpleName());

					if (engine instanceof WebEngine) {
						WebEngine web_engine = (WebEngine) engine;
						if (all || Collections.binarySearch(fields,
								FIELD_SUBSCRIPTION_ENGINE_URL) >= 0) {
							mapEngine.put(FIELD_SUBSCRIPTION_ENGINE_URL,
									web_engine.getSearchUrl(true));
						}
						if (all || Collections.binarySearch(fields,
								FIELD_SUBSCRIPTION_ENGINE_AUTHMETHOD) >= 0) {
							mapEngine.put(FIELD_SUBSCRIPTION_ENGINE_AUTHMETHOD,
									web_engine.getAuthMethod());
						}
					}
				}
			} catch (SubscriptionException e) {
			}
		}
		
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_HIGHEST_VERSION) >= 0) {
			map.put(FIELD_SUBSCRIPTION_HIGHEST_VERSION, sub.getHighestVersion());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_NAME_EX) >= 0) {
			map.put(FIELD_SUBSCRIPTION_NAME_EX, sub.getNameEx());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_QUERY_KEY) >= 0) {
			map.put(FIELD_SUBSCRIPTION_QUERY_KEY, sub.getQueryKey());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_REFERER) >= 0) {
			map.put(FIELD_SUBSCRIPTION_REFERER, sub.getReferer());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_TAG_UID) >= 0) {
			map.put(FIELD_SUBSCRIPTION_TAG_UID, sub.getTagID());
		}
		if (all
				|| Collections.binarySearch(fields, FIELD_SUBSCRIPTION_URI) >= 0) {
			map.put(FIELD_SUBSCRIPTION_URI, sub.getURI());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_ANONYMOUS) >= 0) {
			map.put(FIELD_SUBSCRIPTION_ANONYMOUS, sub.isAnonymous());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_AUTO_DL_SUPPORTED) >= 0) {
			map.put(FIELD_SUBSCRIPTION_AUTO_DL_SUPPORTED,
					sub.isAutoDownloadSupported());
		}
		if (all
				|| Collections.binarySearch(fields, FIELD_SUBSCRIPTION_MINE) >= 0) {
			map.put(FIELD_SUBSCRIPTION_MINE, sub.isMine());
		}
		if (all
				|| Collections.binarySearch(fields, FIELD_SUBSCRIPTION_PUBLIC) >= 0) {
			map.put(FIELD_SUBSCRIPTION_PUBLIC, sub.isPublic());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_IS_SEARCH_TEMPLATE) >= 0) {
			map.put(FIELD_SUBSCRIPTION_IS_SEARCH_TEMPLATE, sub.isSearchTemplate());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_SUBSCRIBED) >= 0) {
			map.put(FIELD_SUBSCRIPTION_SUBSCRIBED, sub.isSubscribed());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_UPDATEABLE) >= 0) {
			map.put(FIELD_SUBSCRIPTION_UPDATEABLE, sub.isUpdateable());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_SHAREABLE) >= 0) {
			map.put(FIELD_SUBSCRIPTION_SHAREABLE, sub.isShareable());
		}
		if (all || Collections.binarySearch(fields,
				FIELD_SUBSCRIPTION_RESULTS_COUNT) >= 0) {
			map.put(FIELD_SUBSCRIPTION_RESULTS_COUNT, sub.getResults(false).length);
		}

		SubscriptionHistory history = sub.getHistory();
		if (history != null) {
			
			if (all || Collections.binarySearch(fields,
					FIELD_SUBSCRIPTION_NEWCOUNT) >= 0) {
				map.put(FIELD_SUBSCRIPTION_NEWCOUNT, history.getNumUnread());
			}
			if (all || Collections.binarySearch(fields,
					"nextScanTime") >= 0) {
				map.put("nextScanTime", history.getNextScanTime());
			}
			if (all || Collections.binarySearch(fields,
					"checkFrequency") >= 0) {
				map.put("checkFrequency", history.getCheckFrequencyMins());
			}
			if (all || Collections.binarySearch(fields,
					"consecutiveFails") >= 0) {
				map.put("consecutiveFails", history.getConsecFails());
			}
			if (all || Collections.binarySearch(fields,
					FIELD_SUBSCRIPTION_AUTO_DOWNLOAD) >= 0) {
				map.put(FIELD_SUBSCRIPTION_AUTO_DOWNLOAD, history.isAutoDownload());
			}
			if (all || Collections.binarySearch(fields,
					"authFail") >= 0) {
				map.put("authFail", history.isAuthFail());
			}

			if (all || Collections.binarySearch(fields,
					"error") >= 0) {
				addNotNullToMap(map, "error", history.getLastError());
			}

			if (fields != null && Collections.binarySearch(fields,
					FIELD_SUBSCRIPTION_RESULTS) >= 0) {
				
				List<Map> listResults = new ArrayList();

				SubscriptionResult[] results = sub.getHistory().getResults(false);

				List fieldsResults = args == null ? null : (List) args.get("results-fields");
				boolean allResults = fieldsResults == null || fieldsResults.size() == 0;

				for (SubscriptionResult r : results) {
					listResults.add(buildSubscriptionResultMap(r, fieldsResults, allResults));
				}

				map.put(FIELD_SUBSCRIPTION_RESULTS, listResults);
			}
		}
		return map;
	}

	private static Map buildSubscriptionResultMap(SubscriptionResult r,
			List fieldsResults, boolean allResults) {

		Map jsonMap = r.toJSONMap();
		if (!allResults) {
			jsonMap.keySet().retainAll(fieldsResults);
		}

		return jsonMap;
	}

	private static void addNotNullToMap(Map<String, Object> map,
			String id, Object o) {
		if (o == null) {
			return;
		}
		map.put(id, o);
	}

	private static void method_Tags_Get_List(Map args, Map result) {
		List fields = (List) args.get(ARG_FIELDS);
		boolean all = fields == null || fields.size() == 0;
		if (!all) {
			// sort so we can't use Collections.binarySearch
			Collections.sort(fields);
		}

		List<SortedMap<String, Object>> listTags = new ArrayList<>();

		TagManager tm = TagManagerFactory.getTagManager();

		List<TagType> tagTypes = tm.getTagTypes();
		
		for (TagType tagType : tagTypes) {
			List<Tag> tags = tagType.getTags();
			
			for (Tag tag : tags) {
				SortedMap<String, Object> map = new TreeMap<>();
				if (all || Collections.binarySearch(fields, FIELD_TAG_NAME) >= 0) {
					map.put(FIELD_TAG_NAME, tag.getTagName(true));
				}

				//map.put("taggableTypes", tag.getTaggableTypes()); // com.aelitis.azureus.core.tag.Taggable
				if (all || Collections.binarySearch(fields, FIELD_TAG_COUNT) >= 0) {
					map.put(FIELD_TAG_COUNT, tag.getTaggedCount());
				}
				if (all || Collections.binarySearch(fields, FIELD_TAG_TYPE) >= 0) {
					map.put(FIELD_TAG_TYPE, tag.getTagType().getTagType());
				}
				if (all || Collections.binarySearch(fields, FIELD_TAG_TYPENAME) >= 0) {
					map.put(FIELD_TAG_TYPENAME, tag.getTagType().getTagTypeName(true));
				}

				if (all
						|| Collections.binarySearch(fields, FIELD_TAG_CATEGORY_TYPE) >= 0) {
					if (tag instanceof Category) {
						map.put(FIELD_TAG_CATEGORY_TYPE, ((Category) tag).getType());
					}
				}
				if (all || Collections.binarySearch(fields, FIELD_TAG_UID) >= 0) {
					map.put(FIELD_TAG_UID, tag.getTagUID());
				}
				if (all || Collections.binarySearch(fields, FIELD_TAG_ID) >= 0) {
					map.put(FIELD_TAG_ID, tag.getTagID());
				}
				if (all || Collections.binarySearch(fields, FIELD_TAG_COLOR) >= 0) {
					int[] color = tag.getColor();
					if (color != null) {
						String hexColor = "#";
						for (int c : color) {
							if (c < 0x10) {
								hexColor += "0";
							}
							hexColor += Integer.toHexString(c);
						}
						map.put(FIELD_TAG_COLOR, hexColor);
					}
				}
				if (all
						|| Collections.binarySearch(fields, FIELD_TAG_CANBEPUBLIC) >= 0) {
					map.put(FIELD_TAG_CANBEPUBLIC, tag.canBePublic());
				}
				if (all || Collections.binarySearch(fields, FIELD_TAG_PUBLIC) >= 0) {
					map.put(FIELD_TAG_PUBLIC, tag.isPublic());
				}
				if (all || Collections.binarySearch(fields, FIELD_TAG_VISIBLE) >= 0) {
					map.put(FIELD_TAG_VISIBLE, tag.isVisible());
				}
				if (all || Collections.binarySearch(fields, FIELD_TAG_GROUP) >= 0) {
					map.put(FIELD_TAG_GROUP, tag.getGroup());
				}
				if (all || Collections.binarySearch(fields, FIELD_TAG_AUTO_ADD) >= 0
						|| Collections.binarySearch(fields, FIELD_TAG_AUTO_REMOVE) >= 0) {
					boolean[] auto = tag.isTagAuto();
					if (all
							|| Collections.binarySearch(fields, FIELD_TAG_AUTO_ADD) >= 0) {
						map.put(FIELD_TAG_AUTO_ADD, auto[0]);
					}
					if (all
							|| Collections.binarySearch(fields, FIELD_TAG_AUTO_REMOVE) >= 0) {
						map.put(FIELD_TAG_AUTO_REMOVE, auto[1]);
					}
				}
				
				listTags.add(map);
			}
		}

		String hc = Long.toHexString(StaticUtils.longHashSimpleList(listTags));
		result.put("tags-hc", hc);
		
		String oldHC = MapUtils.getMapString(args, "tags-hc", null);
		if (!hc.equals(oldHC)) {
			result.put("tags", listTags);
		}
	}

	private static void method_Free_Space(Map args, Map result) {
		// RPC v15
/*
   This method tests how much free space is available in a
   client-specified folder.

   Method name: "free-space"

   Request arguments:

   string      | value type & description
   ------------+----------------------------------------------------------
   "path"      | string  the directory to query

   Response arguments:

   string      | value type & description
   ------------+----------------------------------------------------------
   "path"      | string  same as the Request argument
   "size-bytes"| number  the size, in bytes, of the free space in that directory
 */
		Object oPath = args.get("path");
		if (!(oPath instanceof String)) {
			return;
		}
		
		File file = new File((String) oPath);
		while (file != null && !file.exists()) {
			file = file.getParentFile();
		}
		if (file == null) {
			result.put(FIELD_FREESPACE_PATH, oPath);
			result.put(FIELD_FREESPACE_SIZE_BYTES, 0);
			return;
		}
		long space = FileUtil.getUsableSpace(file);
		result.put(FIELD_FREESPACE_PATH, oPath);
		result.put(FIELD_FREESPACE_SIZE_BYTES, space);
	}

	private void method_Queue_Move_Bottom(Map args, Map result) {
		// RPC v14
/*
	string      | value type & description
	------------+----------------------------------------------------------
	"ids"       | array   torrent list, as described in 3.1.
*/
		Object	ids = args.get( "ids" );

		Core core = CoreFactory.getSingleton();
		GlobalManager gm = core.getGlobalManager();

		List<DownloadManager>	dms = getDownloadManagerListFromIDs( gm, ids );

		gm.moveEnd(dms.toArray(new DownloadManager[0]));
	}

	private void method_Queue_Move_Down(Map args, Map result) {
		// RPC v14
/*
	string      | value type & description
	------------+----------------------------------------------------------
	"ids"       | array   torrent list, as described in 3.1.
*/
		Object ids = args.get("ids");

		Core core = CoreFactory.getSingleton();
		GlobalManager gm = core.getGlobalManager();

		List<DownloadManager>	dms = getDownloadManagerListFromIDs( gm, ids );
    Collections.sort(dms, (a, b) -> b.getPosition() - a.getPosition());
    for (DownloadManager dm : dms) {
			gm.moveDown(dm);
		}
	}

	private void method_Queue_Move_Up(Map args, Map result) {
		// RPC v14
/*
	string      | value type & description
	------------+----------------------------------------------------------
	"ids"       | array   torrent list, as described in 3.1.
*/
		Object ids = args.get("ids");

		Core core = CoreFactory.getSingleton();
		GlobalManager gm = core.getGlobalManager();

		List<DownloadManager>	dms = getDownloadManagerListFromIDs( gm, ids );
    Collections.sort(dms, (a, b) -> a.getPosition() - b.getPosition());
    for (DownloadManager dm : dms) {
			gm.moveUp(dm);
		}
	}

	private void method_Queue_Move_Top(Map args, Map result) {
		// RPC v14
/*
	string      | value type & description
	------------+----------------------------------------------------------
	"ids"       | array   torrent list, as described in 3.1.
*/
		Object	ids = args.get( "ids" );

		Core core = CoreFactory.getSingleton();
		GlobalManager gm = core.getGlobalManager();

		List<DownloadManager>	dms = getDownloadManagerListFromIDs( gm, ids );

		gm.moveTop(dms.toArray(new DownloadManager[0]));
	}

	private void method_Blocklist_Update(Map args, Map result) {
		// TODO
		log("blocklist-update not supported");
	}
	
	private void
	method_Torrent_Rename_Path(
			Map args, 
			Map result)
	{
		/*
   Request arguments:

   string                           | value type & description
   ---------------------------------+-------------------------------------------------
   "ids"                            | array      the torrent torrent list, as described in 3.1
                                    |            (must only be 1 torrent)
   "path"                           | string     the path to the file or folder that will be renamed
   "name"                           | string     the file or folder's new name

   Response arguments: "path", "name", and "id", holding the torrent ID integer
		 */
		if ( trace_param.getValue() ){
			log( "unhandled method: torrent-rename-path - " + args );
		}
	}



	private void 
	method_Torrent_Set_Location(
			Map args, 
			Map result)
	throws IOException, DownloadException
	{
		/*
 Request arguments:

 string                     | value type & description
 ---------------------------+-------------------------------------------------
 "ids"                      | array      torrent list, as described in 3.1
 "location"                 | string     the new torrent location
 "move"                     | boolean    if true, move from previous location.
                            |            otherwise, search "location" for files
                            |            (default: false)

 Response arguments: none
		 */
		checkUpdatePermissions();
		
		Object	ids = args.get( "ids" );

		boolean	moveData = getBoolean( args.get( "move" ));
		String sSavePath = (String) args.get("location");
		
		List<DownloadStub>	downloads = getDownloads( ids, false );

		File fSavePath = new File(sSavePath);

		for ( DownloadStub download_stub: downloads ){
			
			Download download = destubbify( download_stub );
			
			if (moveData) {
				Torrent torrent = download.getTorrent();
				if (torrent == null || torrent.isSimpleTorrent()
						|| fSavePath.getParentFile() == null) {
					download.moveDataFiles(fSavePath);
				} else {
					download.moveDataFiles(fSavePath.getParentFile(), fSavePath.getName());
				}
			} else {
  			DownloadManager dm = PluginCoreUtils.unwrap(download);
  			
  			// This is copied from TorrentUtils.changeDirSelectedTorrent
  			
  			int state = dm.getState();
  			if (state == DownloadManager.STATE_STOPPED) {
  				if (!dm.filesExist(true)) {
  					state = DownloadManager.STATE_ERROR;
  				}
  			}
  
  			if (state == DownloadManager.STATE_ERROR) {
  				
  				dm.setTorrentSaveDir(sSavePath);
  				
  				boolean found = dm.filesExist(true);
  				if (!found && dm.getTorrent() != null
  						&& !dm.getTorrent().isSimpleTorrent()) {
  					String parentPath = fSavePath.getParent();
  					if (parentPath != null) {
  						dm.setTorrentSaveDir(parentPath);
  						found = dm.filesExist(true);
  						if (!found) {
  							dm.setTorrentSaveDir(sSavePath);
  						}
  					}
  				}
  
  
  				if (found) {
  					dm.stopIt(DownloadManager.STATE_STOPPED, false, false);
  
  					dm.setStateQueued();
  				}
  			}
			}
		}
	}

	private void 
	method_Torrent_Set(
		String		session_id,
		Map 		args, 
		Map 		result) 
	{
		Object	ids = args.get( "ids" );
		
		handleRecentlyRemoved( session_id, args, result );
		
		List<DownloadStub>	downloads = getDownloads( ids, false );
		
		// RPC v5
		// Not used: Number bandwidthPriority = getNumber("bandwidthPriority", null);

		Number speed_limit_down = StaticUtils.getNumber(
				args.get(FIELD_TORRENT_DOWNLOAD_LIMIT),
				StaticUtils.getNumber(args.get(TR_PREFS_KEY_DSPEED_KBps),
						StaticUtils.getNumber(args.get("speedLimitDownload"))));
		Boolean downloadLimited = getBoolean(FIELD_TORRENT_DOWNLOAD_LIMITED, null);

		List files_wanted 		= (List)args.get( "files-wanted" );
		List files_unwanted 	= (List)args.get( "files-unwanted" );

		// RPC v5
		/** true if session upload limits are honored */
		// Not Used: Boolean honorsSessionLimits = getBoolean("honorsSessionLimits", null);

		
		// "location"            | string     new location of the torrent's content
		String location = (String) args.get("location");
		
		// RPC v16
		List labels = (List) args.get(FIELD_TORRENT_LABELS); 

		// Not Implemented: By default, Vuze automatically adjusts mac connections per torrent based on bandwidth and seeding state
		// "peer-limit"          | number     maximum number of peers
		
		List priority_high		= (List)args.get( "priority-high" );
		List priority_low		= (List)args.get( "priority-low" );
		List priority_normal	= (List)args.get( "priority-normal" );

		List file_infos 		= (List)args.get(FIELD_TORRENT_FILES);
		
		// RPC v14
		// "queuePosition"       | number     position of this torrent in its queue [0...n)
		Number queuePosition = StaticUtils.getNumber(FIELD_TORRENT_POSITION, null);

		// RPC v10
		// "seedIdleLimit"       | number     torrent-level number of minutes of seeding inactivity

		// RPC v10: Not used, always TR_IDLELIMIT_GLOBAL
		// "seedIdleMode"        | number     which seeding inactivity to use.  See tr_inactvelimit (OR tr_idlelimit and TR_IDLELIMIT_*)

		// RPC v5: Not Supported
		// "seedRatioLimit"      | double     torrent-level seeding ratio

		// RPC v5: Not Supported
		// "seedRatioMode"       | number     which ratio to use.  See tr_ratiolimit

		// RPC v10
		// "trackerAdd"          | array      strings of announce URLs to add
		List trackerAddList = (List) args.get("trackerAdd");

		// RPC v10: TODO
		// "trackerRemove"       | array      ids of trackers to remove
		// List trackerRemoveList = (List) args.get("trackerRemove");

		// RPC v10: TODO
		// "trackerReplace"      | array      pairs of <trackerId/new announce URLs>

		// "uploadLimit"         | number     maximum upload speed (KBps)
		Number speed_limit_up = StaticUtils.getNumber(
				args.get("uploadLimit"),
				StaticUtils.getNumber(args.get(TR_PREFS_KEY_USPEED_KBps),
						StaticUtils.getNumber(args.get("speedLimitUpload"))));

		// "uploadLimited"       | boolean    true if "uploadLimit" is honored
		Boolean uploadLimited = getBoolean("uploadLimited", null);
		
		// RPC Vuze
		// "tagAdd"             | array       array of tags to add to torrent
		List tagAddList = (List) args.get("tagAdd");
		List tagRemoveList = (List) args.get("tagRemove");

		Long	l_uploaded_ever		= (Long)args.get(FIELD_TORRENT_UPLOADED_EVER);
		Long	l_downloaded_ever 	= (Long)args.get(FIELD_TORRENT_DOWNLOADED_EVER);
		
		long	uploaded_ever 	= l_uploaded_ever==null?-1: l_uploaded_ever;
		long	downloaded_ever = l_downloaded_ever==null?-1: l_downloaded_ever;

		String name = (String) args.get("name");


		for ( DownloadStub download_stub: downloads ){
						
			try{
				Download	download = destubbify( download_stub );
				
				Torrent t = download.getTorrent();
				
				if ( t == null ){
					
					continue;
				}
	
				if (location != null) {
					File file = new File(location);
					if (!file.isFile()) {
						try {
							download.moveDataFiles(file);
						} catch (DownloadException e) {
							Debug.out(e);
						}
					}
				}
				
				if (name != null) {
					DownloadManager core_download = PluginCoreUtils.unwrap(download);
					core_download.getDownloadState().setDisplayName(name);
				}
				
				if (queuePosition != null) {
					download.moveTo(queuePosition.intValue());
				}
				
				if (trackerAddList != null) {
					for (Object oTracker : trackerAddList) {
						if (oTracker instanceof String) {
							String aTracker = (String) oTracker;
							TorrentUtils.announceGroupsInsertFirst(PluginCoreUtils.unwrap(t), aTracker);
						}
					}
				}
				
				
				if ( speed_limit_down != null && Boolean.TRUE.equals(downloadLimited) ){
					
					download.setDownloadRateLimitBytesPerSecond( speed_limit_down.intValue());
				} else if (Boolean.FALSE.equals(downloadLimited)) {
	
					download.setDownloadRateLimitBytesPerSecond(0);
				}
				
				if ( speed_limit_up != null && Boolean.TRUE.equals(uploadLimited) ){
					
					download.setUploadRateLimitBytesPerSecond( speed_limit_up.intValue());
				} else if (Boolean.FALSE.equals(uploadLimited)) {
	
					download.setUploadRateLimitBytesPerSecond(0);
				}			
				
				if (tagAddList != null) {
					TagManager tm = TagManagerFactory.getTagManager();

					if (tm.isEnabled()) {

						TagType tt = tm.getTagType(TagType.TT_DOWNLOAD_MANUAL);

						for (Object oTagToAdd : tagAddList) {
							if (oTagToAdd != null) {
								addTagToDownload(download, oTagToAdd, tt);
							}

						}
					}
				}
				
				if (tagRemoveList != null) {
					TagManager tm = TagManagerFactory.getTagManager();

					if (tm.isEnabled()) {

						TagType ttManual = tm.getTagType(TagType.TT_DOWNLOAD_MANUAL);
						TagType ttCategory = tm.getTagType(TagType.TT_DOWNLOAD_CATEGORY);

						for (Object oTagToAdd : tagRemoveList) {
							if (oTagToAdd instanceof String) {
								Tag tag = ttManual.getTag((String) oTagToAdd, true);
								if (tag != null) {
									tag.removeTaggable(PluginCoreUtils.unwrap(download));
								}
								tag = ttCategory.getTag((String) oTagToAdd, true);
								if (tag != null) {
									tag.removeTaggable(PluginCoreUtils.unwrap(download));
								}
							} else if (oTagToAdd instanceof Number) {
								int uid = ((Number) oTagToAdd).intValue();
								Tag tag = ttManual.getTag(uid);
								if (tag != null) {
									tag.removeTaggable(PluginCoreUtils.unwrap(download));
								}
								tag = ttCategory.getTag(uid);
								if (tag != null) {
									tag.removeTaggable(PluginCoreUtils.unwrap(download));
								}
							}

						}
					}
				}
				
				if (labels != null) {
					TagManager tm = TagManagerFactory.getTagManager();
					TagType ttManual = tm.getTagType( TagType.TT_DOWNLOAD_MANUAL );

					DownloadManager download_core = PluginCoreUtils.unwrap(download);
					List<Tag> tags = ttManual.getTagsForTaggable(download_core);

					if (tags != null) {
						Map<String, Tag> existingLabels = new HashMap<>(); 
						for (Tag tag : tags) {
							existingLabels.put(tag.getTagName(), tag);
						}

						for (Object o : labels) {
							if (!(o instanceof String)) {
								continue;
							}
							String label = (String) o;
							if (existingLabels.remove(label) == null) {
								addTagToDownload(download, label, ttManual);
							}
						}
						
						// remaining need to be removed
						for (Tag tagToRemove : existingLabels.values()) {
							tagToRemove.removeTaggable(download_core);
						}
					}
				}
									
				DiskManagerFileInfo[] files = download.getDiskManagerFileInfo();
					
				if ( files_unwanted != null ){

					for (Object o : files_unwanted) {

						int index = ((Long) o).intValue();

						if ( index >= 0 && index <= files.length ){

							files[index].setSkipped( true );
						}
					}
				}
				
				if ( files_wanted != null ){

					for (Object o : files_wanted) {

						int index = ((Long) o).intValue();

						if ( index >= 0 && index <= files.length ){

							files[index].setSkipped( false );
						}
					}
				}
				
				if ( priority_high != null ){

					for (Object o : priority_high) {

						int index = ((Long) o).intValue();

						if ( index >= 0 && index <= files.length ){

							files[index].setNumericPriority( DiskManagerFileInfo.PRIORITY_HIGH );
						}
					}
				}
				
				if ( priority_normal != null ){

					for (Object o : priority_normal) {

						int index = ((Long) o).intValue();
						
						if ( index >= 0 && index <= files.length ){
							
							files[index].setNumericPriority( DiskManagerFileInfo.PRIORITY_NORMAL );
						}
					}
				}
				
				if ( priority_low != null ){

					for (Object o : priority_low) {

						int index = ((Long) o).intValue();
						
						if ( index >= 0 && index <= files.length ){
							
							files[index].setNumericPriority( DiskManagerFileInfo.PRIORITY_LOW );
						}
					}
				}
				
				if ( uploaded_ever != -1 || downloaded_ever != -1 ){
					
						// new method in 4511 B31
					
					try{
						download.getStats().resetUploadedDownloaded( uploaded_ever, downloaded_ever );
						
					}catch( Throwable e ){
					}
				}
				
				if ( file_infos != null ){
					
					boolean	paused_it = false;
					
					try{
						for (Object fileInfo : file_infos) {

							Map file_info = (Map) fileInfo;
							
							int index = ((Number)file_info.get(FIELD_FILES_INDEX)).intValue();
							
							if ( index < 0 || index >= files.length ){
							
								throw( new IOException( "File index '" + index + "' invalid for '" + download.getName()+ "'" ));
							}
							
							//String	path 	= (String)file_info.get( "path" ); don't support changing this yet
							
							String  new_name	= (String)file_info.get( "name" );		// terminal name of the file (NOT the whole relative path+name)
							
							if ( new_name == null || new_name.trim().length() == 0 ){
								
								throw( new IOException( "'name' is mandatory"));
							}
							
							new_name = new_name.trim();
							
							DiskManagerFileInfo file = files[index];
							
							File existing = file.getFile( true );
							
							if ( existing.getName().equals( new_name )){
								
								continue;
							}
							
							if ( !download.isPaused()){
								
								download.pause();
								
								paused_it = true;
							}
							
							File new_file = new File( existing.getParentFile(), new_name );
							
							if ( new_file.exists()){
								
								throw( new IOException( "new file '" + new_file + "' already exists" ));
							}
							
							file.setLink( new_file );
						}
					}finally{
						
						if ( paused_it ){
							
							download.resume();
						}
					}
				}
			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}
	}

	private static void addTagToDownload(Download download, Object tagToAdd, TagType tt) {
		Tag tag = null;
		if (tagToAdd instanceof String) {
			String tagNameToAdd = ((String) tagToAdd).trim();

			if (tagNameToAdd.length() == 0) {
				return;
			}
			tag = tt.getTag(tagNameToAdd, true);

			if (tag == null) {
				try {
					tag = tt.createTag(tagNameToAdd, true);
				} catch (Throwable e) {
					Debug.out(e);
				}
			}
		} else if (tagToAdd instanceof Number) {
			tag = tt.getTag(((Number) tagToAdd).intValue());
		}

		if (tag != null) {
			tag.addTaggable(PluginCoreUtils.unwrap(download));
		}
	}

	private void
	method_Torrent_Reannounce(
			Map args, 
			Map result)
	throws IOException
	{
		checkUpdatePermissions();
		
		Object	ids = args.get( "ids" );

		List<DownloadStub>	downloads = getDownloads( ids, false );

		for ( DownloadStub download_stub: downloads ){
						
			try{
				destubbify( download_stub ).requestTrackerAnnounce();

			}catch( Throwable e ){
				
				Debug.out( "Failed to reannounce '" + download_stub.getName() + "'", e );
			}
		}
	}


	private void 
	method_Torrent_Remove(
			Map args, 
			Map result) 
	throws IOException 
	{
		/*
 Request arguments:

 string                     | value type & description
 ---------------------------+-------------------------------------------------
 "ids"                      | array      torrent list, as described in 3.1
 "delete-local-data"        | boolean    delete local data. (default: false)

 Response arguments: none
		 */
		checkUpdatePermissions();
		
		Object	ids = args.get( "ids" );

		boolean	delete_data = getBoolean( args.get( "delete-local-data" ));
		
		List<DownloadStub>	downloads = getDownloads( ids, true );

		for ( DownloadStub download_stub: downloads ){
			
			try{
				if ( download_stub instanceof MagnetDownload ){
					
					synchronized( magnet_downloads ){
						
						magnet_downloads.remove( download_stub );
					}
					
					addRecentlyRemoved( download_stub );
					
				}else{
					Download download = destubbify( download_stub );
					
					int	state = download.getState();
					
					if ( state != Download.ST_STOPPED ){
					
						download.stop();
					}
					
					if ( delete_data ){
						
						download.remove( true, true );
						
					}else{
						
						download.remove();	
					}
				
					addRecentlyRemoved( download );
				}
			}catch( Throwable e ){
				
				Debug.out( "Failed to remove download '" + download_stub.getName() + "'", e );
			}
		}
	}

	private void 
	method_Torrent_Verify(
			Map args, 
			Map result)
	throws IOException 
	{
		checkUpdatePermissions();
		
		Object	ids = args.get( "ids" );

		List<DownloadStub>	downloads = getDownloads( ids, false );

		for ( DownloadStub download_stub: downloads ){
			
			try{
				Download download = destubbify( download_stub );
				
				int	state = download.getState();
				
				if ( state != Download.ST_STOPPED ){
				
					download.stop();
				}
				
				download.recheckData();
				
			}catch( Throwable e ){
			}
		}
	}

	private void 
	method_Torrent_Stop(
			Map args, 
			Map result)
	throws IOException 
	{
		checkUpdatePermissions();
		
		Object	ids = args.get( "ids" );

		List<DownloadStub>	downloads = getDownloads( ids, false );

		for ( DownloadStub download_stub: downloads ){
			
			if ( !download_stub.isStub()){
				
				try{
					Download download = destubbify( download_stub );
					
					int	state = download.getState();
					
					if ( state != Download.ST_STOPPED ){
					
						download.stop();
					}
				}catch( Throwable e ){
				}
			}
		}
	}

	private void 
	method_Torrent_Start(
			Map args, 
			Map result) 
	throws IOException 
	{
		checkUpdatePermissions();
		
		Object	ids = args.get( "ids" );

		List<DownloadStub>	downloads = getDownloads( ids, false );

		for ( DownloadStub download_stub: downloads ){
			
			try{
				Download download = destubbify( download_stub );
				
				int	state = download.getState();
				if (state == Download.ST_ERROR) {
					// Stop on an error torrent should stop it immediately
					download.stop();
				}
				
				if ( state != Download.ST_DOWNLOADING && state != Download.ST_SEEDING ){
				
					download.restart();
				}
			}catch( Throwable e ){
			}
		}
	}

	private void 
	method_Torrent_Start_Now(
			Map args, 
			Map result) 
	throws IOException 
	{
		checkUpdatePermissions();
		
		Object	ids = args.get( "ids" );

		List<DownloadStub>	downloads = getDownloads( ids, false );

		for ( DownloadStub download_stub: downloads ){
			
			try{
				Download download = destubbify( download_stub );
				
				download.startDownload(true);

			}catch( Throwable e ){
			}
		}
	}

	private void
	processVuzeFileAdd(
		final 	Map args, 
		Map 	result )
	
		throws IOException, TextualException
	{
		checkUpdatePermissions();

		VuzeFileHandler vfh = VuzeFileHandler.getSingleton();

		VuzeFile vf = null;
		
		String url = (String) args.get( "filename" );

		Throwable last_error = null;
				
		if ( url != null ){

			try{
				File f = new File( new URI( url ));
				
				if ( f.exists()){
					
					vf = vfh.loadVuzeFile( f );
					
					if ( vf == null ){
						
						throw( new TextualException( "Decode failed - invalid Vuze file" ));
					}
				}
			}catch( Throwable e ){
				
				last_error = e;
			}
			
			if ( vf == null && last_error == null ){
				
				try{
					vf = vfh.loadVuzeFile( new ResourceDownloaderFactoryImpl().create( new URL( url )).download());
					
				}catch( Throwable e ){
					
					last_error = e;
				}
			}
		}
		
		if ( vf == null && last_error == null ){
			
			try{
				String metainfoString = (String) args.get("metainfo");
	
				byte[]	metainfoBytes;
			
				if ( metainfoString != null ){
			
					metainfoBytes = Base64.decode( metainfoString.replaceAll("[\r\n]+", "") );
							
					vf = vfh.loadVuzeFile( metainfoBytes );
				
					if ( vf == null ){
						
						throw( new TextualException( "Decode failed - invalid Vuze file" ));
					}
				}else{
					
					throw( new TextualException( "Missing parameter" ));
				}
			}catch( Throwable e ){
				
				last_error = e;
			}
		}
		
		if ( vf != null ){
			
  			VuzeFileComponent[] comps = vf.getComponents();

			for ( VuzeFileComponent comp: comps ){
				
				if ( comp.getType() != VuzeFileComponent.COMP_TYPE_METASEARCH_TEMPLATE ){
					
					throw( new TextualException( "Unsupported Vuze File component type: " + comp.getTypeName()));
				}
			}
			
  			vfh.handleFiles( new VuzeFile[]{ vf }, VuzeFileComponent.COMP_TYPE_METASEARCH_TEMPLATE );
  					  			
  			String added_templates = "";
  					
  			for ( VuzeFileComponent comp: comps ){
  				
  				if ( comp.isProcessed()){
  				
  					Engine e = (Engine)comp.getData( Engine.VUZE_FILE_COMPONENT_ENGINE_KEY );
  					
  					if ( e != null ){
  						
  						added_templates += (added_templates.isEmpty()?"":", ") + e.getName();
  					}
  				}
  			}
  			
  			result.put( "msg", "Search templates added: " + added_templates );
  			
		}else{
			
			if ( last_error == null ){
				
				throw( new TextualException( "Unspecified error occurred" ));
				
			}else{
				
				if ( last_error instanceof TextualException ){
					
					throw((TextualException)last_error);
					
				}else{
				
					throw( new TextualException( "Vuze file addition failed: " + StaticUtils.getCausesMesssages( last_error )));
				}
			}
		}
	}
	
	private final Map<String,ConsoleContext>		console_contexts = new HashMap<>();

	private void
	processConsole(
		Map 	args, 
		Map 	result )
	
		throws IOException, TextualException
	{
		checkUpdatePermissions();

		String uid = (String)args.get( "instance_id" );
		
		if ( uid == null ){
			
			throw( new IOException( "instance_id missing" ));
		}
		
		ConsoleContext	console;
		
		synchronized( console_contexts ){
			
			console = console_contexts.get( uid );
		
			if ( console == null ){
				
				console = new ConsoleContext( console_contexts, uid );
				
				console_contexts.put( uid, console );
			}
		}
		
		List<String>	lines = console.process( args );
				
		result.put( "lines", lines );
	}
	
	private void 
	method_Torrent_Add(
		final Map args, 
		Map result,
		boolean xmlEscape) 
				
		throws IOException, DownloadException, TextualException
 {
		/*
		   Request arguments:

		   key                  | value type & description
		   ---------------------+-------------------------------------------------
		   "cookies"            | string      pointer to a string of one or more cookies.
		   "download-dir"       | string      path to download the torrent to
		   "filename"           | string      filename or URL of the .torrent file
		   "metainfo"           | string      base64-encoded .torrent content
		   "paused"             | boolean     if true, don't start the torrent
		   "peer-limit"         | number      maximum number of peers
		   "bandwidthPriority"  | number      torrent's bandwidth tr_priority_t 
		   "files-wanted"       | array       indices of file(s) to download
		   "files-unwanted"     | array       indices of file(s) to not download
		   "priority-high"      | array       indices of high-priority file(s)
		   "priority-low"       | array       indices of low-priority file(s)
		   "priority-normal"    | array       indices of normal-priority file(s)

		   Either "filename" OR "metainfo" MUST be included.
		   All other arguments are optional.

		 	additional vuze specific parameters
			
			 "vuze_category"	| string (optional category name)
			 "vuze_tags"		| array  (optional list of tags)
			 "name"	        | string (optional friendly name to use instead of url/hash)
			 
		   The format of the "cookies" should be NAME=CONTENTS, where NAME is the
		   cookie name and CONTENTS is what the cookie should contain.
		   Set multiple cookies like this: "name1=content1; name2=content2;" etc. 
		   <http://curl.haxx.se/libcurl/c/curl_easy_setopt.html#CURLOPTCOOKIE>

		   Response arguments: on success, a "torrent-added" object in the
		                       form of one of 3.3's tr_info objects with the
		                       fields for id, name, and hashString.
		 */
		checkUpdatePermissions();

	 	Map<String, Object> mapTorrent = null;

		if ( args.containsKey("metainfo") ){

			// .remove to increase chances of metainfo being GC'd if needed
			byte[] metainfoBytes = decodeBase64(((String) args.remove("metainfo")));

			BDecoder decoder = new BDecoder();
			decoder.setVerifyMapOrder(true);
			mapTorrent = decoder.decodeByteArray(metainfoBytes);
			

			VuzeFileHandler vfh = VuzeFileHandler.getSingleton();
	
			if ( vfh != null ){
				
				VuzeFile vf = vfh.loadVuzeFile( mapTorrent );
	
				if ( vf != null ){
					
		  			VuzeFileComponent[] comps = vf.getComponents();

					for ( VuzeFileComponent comp: comps ){
						
						if ( comp.getType() != VuzeFileComponent.COMP_TYPE_METASEARCH_TEMPLATE ){
							
							throw( new TextualException( "Unsupported Vuze File component type: " + comp.getTypeName()));
						}
					}
					
		  			vfh.handleFiles( new VuzeFile[]{ vf }, VuzeFileComponent.COMP_TYPE_METASEARCH_TEMPLATE );
		  					  			
		  			String added_templates = "";
		  					
		  			for ( VuzeFileComponent comp: comps ){
		  				
		  				if ( comp.isProcessed()){
		  				
		  					Engine e = (Engine)comp.getData( Engine.VUZE_FILE_COMPONENT_ENGINE_KEY );
		  					
		  					if ( e != null ){
		  						
		  						added_templates += (added_templates.isEmpty()?"":", ") + e.getName();
		  					}
		  				}
		  			}
		  			
		  			if ( added_templates.length() == 0 ){
		  				
		  				throw( new TextualException( "No search template(s) added" ));
		  				
		  			}else{
		  				
		  				throw( new TextualException( "Installed search template(s): " + added_templates ));
		  			}
				}
			}
		}
		
		Torrent 		torrent = null;
		DownloadStub	download = null;
		
		String url = (String) args.get("filename");
				
		final boolean add_stopped = getBoolean(args.get("paused"));
		
		String download_dir = (String) args.get(TR_PREFS_KEY_DOWNLOAD_DIR);
		
		final File file_Download_dir = download_dir == null ? null : new File(download_dir);

		// peer-limit not used
		//getNumber(args.get("peer-limit"), 0);

		// bandwidthPriority not used
		//getNumber(args.get("bandwidthPriority"), TR_PRI_NORMAL);
				
		final DownloadWillBeAddedListener add_listener =
			new DownloadWillBeAddedListener() {
				@Override
				public void initialised(Download download) {
					int numFiles = download.getDiskManagerFileCount();
					List files_wanted = getList(args.get("files-wanted"));
					List files_unwanted = getList(args.get("files-unwanted"));
	
					boolean[] toDelete = new boolean[numFiles]; // all false
	
					int numWanted = files_wanted.size();
					if (numWanted != 0 && numWanted != numFiles) {
						// some wanted -- so, set all toDelete and reset ones in list
						Arrays.fill(toDelete, true);
						for (Object oWanted : files_wanted) {
							int idx = StaticUtils.getNumber(oWanted, -1).intValue();
							if (idx >= 0 && idx < numFiles) {
								toDelete[idx] = false;
							}
						}
					}
					for (Object oUnwanted : files_unwanted) {
						int idx = StaticUtils.getNumber(oUnwanted, -1).intValue();
						if (idx >= 0 && idx < numFiles) {
							toDelete[idx] = true;
						}
					}
	
					for (int i = 0; i < toDelete.length; i++) {
						if (toDelete[i]) {
							download.getDiskManagerFileInfo(i).setDeleted(true);
						}
					}
	
					List priority_high = getList(args.get("priority-high"));
					for (Object oHighPriority : priority_high) {
						int idx = StaticUtils.getNumber(oHighPriority, -1).intValue();
						if (idx >= 0 && idx < numFiles) {
							download.getDiskManagerFileInfo(idx).setNumericPriority(
									DiskManagerFileInfo.PRIORITY_HIGH);
						}
					}
					List priority_low = getList(args.get("priority-low"));
					for (Object oLowPriority : priority_low) {
						int idx = StaticUtils.getNumber(oLowPriority, -1).intValue();
						if (idx >= 0 && idx < numFiles) {
							download.getDiskManagerFileInfo(idx).setNumericPriority(
									DiskManagerFileInfo.PRIORITY_LOW);
						}
					}
					// don't need priority-normal if they are normal by default.
					
					// handle initial categories/tags
					
					try{
						String vuze_category = (String)args.get( "vuze_category" );
	
						if ( vuze_category != null ){
							
							vuze_category = vuze_category.trim();
							
							if ( vuze_category.length() > 0 ){
								
								TorrentAttribute	ta_category	= plugin_interface.getTorrentManager().getAttribute(TorrentAttribute.TA_CATEGORY);
								
								download.setAttribute( ta_category, vuze_category );
							}
						}
						
						List<String>	vuze_tags = (List<String>)args.get( "vuze_tags" );
						
						if ( vuze_tags != null ){
							
							TagManager tm = TagManagerFactory.getTagManager();
							
							if ( tm.isEnabled()){
								
								TagType tt = tm.getTagType( TagType.TT_DOWNLOAD_MANUAL );
								
								for ( String tag_name: vuze_tags ){
									
									addTagToDownload(download, tag_name, tt);
								}
							}
						}				
					}catch( Throwable e ){
						
						e.printStackTrace();
					}
				}
			};
		
		
		boolean duplicate = false;

		if ( mapTorrent != null ){
			try {
				// Note: adding the torrent will cause another deserialize
				//       because the core saves the TOTorrent to disk, and then
				//       reads it.
				TOTorrent toTorrent = TOTorrentFactory.deserialiseFromMap(mapTorrent);
				torrent = new TorrentImpl(plugin_interface, toTorrent);
				
				com.biglybt.pif.download.DownloadManager dm = plugin_interface.getDownloadManager();
				download = dm.getDownload( torrent );
				duplicate = download != null;
				
			} catch (Throwable e) {

				e.printStackTrace();
				
				//System.err.println("decode of " + new String(Base64.encode(metainfoBytes), "UTF8"));

				throw (new IOException("torrent download failed: "
						+ StaticUtils.getCausesMesssages(e)));
			}
		} else if (url == null) {

			throw (new IOException("url missing"));

		} else {

			url = url.trim().replaceAll(" ", "%20");

			// hack due to core bug - have to add a bogus arg onto magnet uris else they fail to parse

			String lc_url = url.toLowerCase( Locale.US );
			
			if ( lc_url.startsWith("magnet:")) {

				url += "&dummy_param=1";
				
			} else if (!lc_url.startsWith("http")) {
				
				url = UrlUtils.parseTextForURL(url, true, true);
			}
			
			byte[] hashFromMagnetURI = getHashFromMagnetURI(url);
			if (hashFromMagnetURI != null) {
				com.biglybt.pif.download.DownloadManager dm = plugin_interface.getDownloadManager();
				download = dm.getDownload(hashFromMagnetURI);
				duplicate = download != null;
			}

			if (download == null) {
			URL torrent_url;
			try {
			 torrent_url = new URL(url);
			} catch (MalformedURLException mue) {
				throw new TextualException("The torrent URI was not valid");
			}
			
			try{
				TorrentManager torrentManager = plugin_interface.getTorrentManager();

				final TorrentDownloader dl = torrentManager.getURLDownloader(torrent_url, null, null);

				Object cookies = args.get("cookies");
				
				if ( cookies != null ){
					
					dl.setRequestProperty("URL_Cookie", cookies);
				}

				boolean is_magnet = torrent_url.getProtocol().equalsIgnoreCase( "magnet" );
				
				if ( is_magnet ){
						
					TimerEvent	magnet_event = null;

					final Object[]	f_result = { null };
					
					try{
						final AESemaphore sem = new AESemaphore( "magnetsem" );
						final URL f_torrent_url = torrent_url;
						final String f_name = (String) args.get("name");
						magnet_event = SimpleTimer.addEvent(
							"magnetcheck",
							SystemTime.getOffsetTime( 10*1000 ),
							new TimerEventPerformer() 
							{
								@Override
								public void
								perform(
									TimerEvent event )
								{
									synchronized( f_result ){
										
										if ( f_result[0] != null ){
											
											return;
										}
										
										MagnetDownload magnet_download = new MagnetDownload(XMWebUIPlugin.this, f_torrent_url, f_name );
										
										byte[]	hash = magnet_download.getTorrentHash();
										
										synchronized( magnet_downloads ){
											
											boolean	duplicate = false;
											
											Iterator<MagnetDownload> it =  magnet_downloads.iterator();
											
											while( it.hasNext()){
												
												MagnetDownload md = it.next();
												
												if ( hash.length > 0 && Arrays.equals( hash, md.getTorrentHash())){
													
													if ( md.getError() == null ){
														
														duplicate = true;
													
														magnet_download = md;
													
														break;
														
													}else{
														
														it.remove();
														
														addRecentlyRemoved( md );
													}
												}
											}
											
											if ( !duplicate ){
											
												magnet_downloads.add( magnet_download );
											}
										}
										
										f_result[0] = magnet_download;
									}
							
									sem.release();
								}
							});
											
						new AEThread2( "magnetasync" )
						{
							@Override
							public void
							run()
							{
								try{
									Torrent torrent = dl.download(Constants.DEFAULT_ENCODING);
									
									synchronized( f_result ){
										
										if ( f_result[0] == null ){
										
											f_result[0] = torrent;
											
										}else{
											
											MagnetDownload md = (MagnetDownload)f_result[0];										
											
											boolean	already_removed;
											
											synchronized( magnet_downloads ){
												
												already_removed = !magnet_downloads.remove( md );
											}
											
											if ( !already_removed ){
												
												addRecentlyRemoved( md );
											
												addTorrent( torrent, file_Download_dir, add_stopped, add_listener );
											}
										}
									}
								}catch( Throwable e ){
									
									synchronized( f_result ){
										
										if ( f_result[0] == null ){
										
											f_result[0] = e;
										
										}else{
										
											MagnetDownload md = (MagnetDownload)f_result[0];
											
											md.setError( e );
										}
									}
								}finally{
									
									sem.release();
								}
							}
						}.start();
						
						sem.reserve();
						
						Object res;
						
						synchronized( f_result ){
							
							res = f_result[0];
						}
						
						if ( res instanceof Torrent ){
							
							torrent = (Torrent)res;
							
						}else if ( res instanceof Throwable ){
							
							throw((Throwable)res);
							
						}else{
							
							download 	= (MagnetDownload)res;
							torrent		= null;
						}
					}finally{
						
						if ( magnet_event != null ){
							
							magnet_event.cancel();
						}
					}
				}else{
					
					torrent = dl.download(Constants.DEFAULT_ENCODING);
				}
			}catch( Throwable e ){

				e.printStackTrace();

				throw( new IOException( StaticUtils.getCausesMesssages( e )));
			}
			}
		}

		if ( download == null ){

			download = addTorrent( torrent, file_Download_dir, add_stopped, add_listener );
		}
		
		Map<String, Object> torrent_details = new HashMap<>();

		torrent_details.put("id", getID(download, true));
		torrent_details.put("name", xmlEscape ? StaticUtils.escapeXML(download.getName()) : download.getName());
		torrent_details.put(FIELD_TORRENT_HASH_STRING,
				ByteFormatter.encodeString(download.getTorrentHash()));

		result.put(duplicate ? "torrent-duplicate" : "torrent-added", torrent_details);
	}

	private static byte[] decodeBase64(String s) {
		String newLineCheck = s.substring(0, 90);
		boolean hasNewLine = newLineCheck.indexOf('\r') >= 0
				|| newLineCheck.indexOf('\n') >= 0;
		if (hasNewLine) {
			s = s.replaceAll("[\r\n]+", "");
		}
		return Base64.decode(s);
	}

	private static byte[] getHashFromMagnetURI(String magnetURI) {
		Pattern patXT = Pattern.compile("xt=urn:(?:btih|sha1):([^&]+)");
		Matcher matcher = patXT.matcher(magnetURI);
		if (matcher.find()) {
			return UrlUtils.decodeSHA1Hash(matcher.group(1));
		}
		return null;
	}


	protected List<DownloadStub>
	getAllDownloads(
		boolean	include_magnet_dowloads )
	{
		Download[] 		downloads1 = plugin_interface.getDownloadManager().getDownloads();
		DownloadStub[] 	downloads2 = plugin_interface.getDownloadManager().getDownloadStubs();
		
		MagnetDownload[] 	downloads3;
		
		if ( include_magnet_dowloads ){
			
			synchronized( magnet_downloads ){
				
				downloads3 = magnet_downloads.toArray(new MagnetDownload[0]);
			}
		}else{
			
			downloads3 = new MagnetDownload[0];
		}
		
		List<DownloadStub>	result = new ArrayList<>(downloads1.length + downloads2.length + downloads3.length);
		
		result.addAll( Arrays.asList( downloads1 ));
		result.addAll( Arrays.asList( downloads2 ));
		result.addAll( Arrays.asList( downloads3 ));

		
		return( result );
	}
	
	protected List<DownloadStub>
	getDownloads(
		Object		ids,
		boolean		include_magnet_dowloads )
	{
		List<DownloadStub>	downloads = new ArrayList<>();
		
		List<DownloadStub> 	all_downloads = getAllDownloads( include_magnet_dowloads );

		List<Long>		selected_ids 	= new ArrayList<>();
		List<String>	selected_hashes = new ArrayList<>();
		
		if ( ids == null ){
			
		}else if ( ids instanceof String ){
			
			ids = null;
			
		}else if ( ids instanceof Number ){
			
			selected_ids.add(((Number)ids).longValue());
			
		}else if ( ids instanceof List ){
			
			List l = (List)ids;
			
			for (Object o: l ){
				
				if ( o instanceof Number ){
					
					selected_ids.add(((Number)o).longValue());
					
				}else if ( o instanceof String ){
					
					selected_hashes.add((String)o);
				}
			}
		}
		
		boolean hide_ln = hide_ln_param.getValue();
		
		for( DownloadStub download_stub: all_downloads ){
			
			if ( download_stub.isStub()){
				
				if ( ids == null ){
					
					downloads.add( download_stub );
					
				}else{
					
					long	id = getID( download_stub, true );
					
					if ( selected_ids.contains( id )){
						
						downloads.add( download_stub );
						
					}else{
						
						if ( selected_hashes.contains( ByteFormatter.encodeString( download_stub.getTorrentHash()))){
								
							downloads.add( download_stub );
						}
					}
				}
			}else{
				try{
					Download download = destubbify( download_stub );
				
					if ( hide_ln && download.getFlag( Download.FLAG_LOW_NOISE )){
						
						continue;
					}
					
					if ( download.getFlag( Download.FLAG_METADATA_DOWNLOAD )){
						
						continue;
					}
					
					if ( ids == null ){
						
						downloads.add( download );
						
					}else{
						
						long	id = getID( download, true );
						
						if ( selected_ids.contains( id )){
							
							downloads.add( download );
							
						}else{
							
							Torrent t = download.getTorrent();
							
							if ( t != null ){
								
								if ( selected_hashes.contains( ByteFormatter.encodeString( t.getHash()))){
									
									downloads.add( download );
								}
							}
						}
					}
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
		
		Collections.sort(downloads, (arg0, arg1) -> {
			long res = getID(arg0, true) - getID(arg1, true);

			if (res < 0) {
				return (-1);
			} else if (res > 0) {
				return (1);
			} else {
				return (0);
			}
		});
		
		return( downloads );
	}
	
	public List<DownloadManager>
	getDownloadManagerListFromIDs(
			GlobalManager gm,
			Object		ids )
	{
		List<DownloadStub> downloads = getDownloads(ids,false);
		
		List<DownloadManager> list = new ArrayList<>(downloads.size());

		for ( DownloadStub downloadStub: downloads ){
			
			try{
				Download download = destubbify( downloadStub );
				if (download != null) {
    			DownloadManager dm = PluginCoreUtils.unwrap(download);
    			if (dm != null) {
    				list.add(dm);
    			}
				}

			}catch( Throwable e ){
				
				Debug.out( "Failed to get dm '" + downloadStub.getName() + "'", e );
			}
		}

		return list;
	}

	protected static List
	getList(
		Object	o )
	{
		if ( o instanceof List ) {
			return (List) o;
		} else {
			return new ArrayList();
		}
	}
	
	protected static boolean
	getBoolean(
		Object	o )
	{
		return getBoolean(o, false);
	}

	protected static Boolean
	getBoolean(
		Object	o,
		Boolean defaultVal )
	{
		if ( o instanceof Boolean ){
			
			return((Boolean)o);
						
		}else if ( o instanceof String ){
			
			return( ((String)o).equalsIgnoreCase( "true" ));
			
		}else if ( o instanceof Number ){
			
			return(((Number)o).intValue()!=0);
			
		}else{
			
			return( defaultVal );
		}
	}
	
	long
	getID(
		DownloadStub		download_stub,
		boolean				allocate_if_new )
	{
		synchronized( this ){
			
			if ( check_ids_outstanding ){
				
				check_ids_outstanding = false;
				
				List<DownloadStub> all_downloads = getAllDownloads( true );

				Collection<Long> all_ids = new HashSet<>();
				
				List<DownloadStub>	dups = new ArrayList<>();
				
				long	max_id = 0;
				
				for( DownloadStub d: all_downloads ){
					
					long	id = getID( d, false );
					
					if ( id <= 0 ){
						
						continue;
					}
					
					max_id = Math.max( max_id, id );
					
					if ( all_ids.contains( id )){
					
						dups.add( d );
						
					}else{
						
						all_ids.add( id );
					}
				}
				
				PluginConfig config = plugin_interface.getPluginconfig();
					
				long	next_id = max_id + 1;
				
				for ( DownloadStub d: dups ){
					
					//System.out.println( "Fixed duplicate id " + getID( d, false ) + " for " + d.getName());
					
					d.setLongAttribute( t_id, next_id++ );
				}
				
				config.setPluginParameter( "xmui.next.id", next_id );

			}
		}
			
			// I was trying to be clever and allocate unique ids for downloads. however,
			// the webui assumes they are consecutive and give a queue index. ho hum
			
		// return( d.getIndex());
		
		long id = download_stub.getLongAttribute( t_id );
			
		if ( id == 0 && allocate_if_new ){
		
			synchronized( this ){
				
				PluginConfig config = plugin_interface.getPluginconfig();
			
				id = config.getPluginLongParameter( "xmui.next.id", 1 );
				
				config.setPluginParameter( "xmui.next.id", id + 1 );
			}
			
			download_stub.setLongAttribute( t_id, id );
		}
		
		//System.out.println( download.getName() + " -> " + id );
		
		return( id );
	}
	
	private static String
	getAZMode()
	{
		return "trial";
	}
	
	private void
	processVuzeTorrentGet(
		TrackerWebPageRequest		request,
		Map 						args,
		Map 						result)
	{		
		Object	ids = args.get( "ids" );
				
		List<DownloadStub>	downloads = getDownloads( ids, true );
				
		List<Map>	torrents = new ArrayList<>(downloads.size());
		
		result.put( "torrents", torrents );
		
		List<Number> requested_files 		= (List<Number>)args.get( "files" );

		String host = (String)request.getHeaders().get( "host" );
		
		for ( DownloadStub download_stub: downloads ){
						
			Map<String,Object>	torrent = new HashMap<>();
			
			torrents.add( torrent );
			
			long id = getID( download_stub, true );

			torrent.put( "id", id );
			
			if ( download_stub.isStub()){
				
				continue;
			}
			
			try{
				Download download = download_stub.destubbify();
				
				DownloadManager dm = PluginCoreUtils.unwrap( download );

				if ( dm == null ){
					
					continue;
				}
									
	
				DiskManagerFileInfo file;
				
				try{
					file = PluginCoreUtils.wrap(dm.getDownloadState().getPrimaryFile());
					
				}catch( DownloadException e ){
					
					continue;
				}
					
				if ( file == null ){
						
					continue;
				}
														
				URL stream_url = PlayUtils.getMediaServerContentURL( file );

				if ( stream_url != null ){
					
					torrent.put(FIELD_FILES_CONTENT_URL, StaticUtils.adjustURL( host, stream_url ));
				}
				
				TOTorrent to_torrent = dm.getTorrent();
				
				if ( to_torrent != null ){
					
					String url = PlatformTorrentUtils.getContentThumbnailUrl( to_torrent );
					
					if ( url != null ){
						
						torrent.put( "thumbnailURL", url );
						
					}else{
				
						byte[] data = PlatformTorrentUtils.getContentThumbnail( to_torrent );
						
						if ( data != null ){
							
							torrent.put( "thumbnailURL", getThumbnailResourceURL( id ));
						}
					}
				}
				
				if ( requested_files != null ){
					
					List<Map> file_info = new ArrayList<>();
						
					torrent.put( "files", file_info );
					
					DiskManagerFileInfo[] files = download.getDiskManagerFileInfo();
					
					if ( requested_files.size() == 0 ){
						
						for ( DiskManagerFileInfo f: files ){
							
							Map f_map = new HashMap();
							
							file_info.add( f_map );
							
							f_map.put( "index", f.getIndex());
							
							URL f_stream_url = PlayUtils.getMediaServerContentURL( f );

							if ( f_stream_url != null ){
								
								f_map.put(FIELD_FILES_CONTENT_URL, StaticUtils.adjustURL( host, f_stream_url ));
							}
						}
					}else{
						
						for ( Number num: requested_files ){
							
							int	index = num.intValue();
							
							if ( index >= 0 && index < files.length ){
								
								DiskManagerFileInfo f = files[index];
								
								Map f_map = new HashMap();
								
								file_info.add( f_map );
								
								f_map.put( "index", f.getIndex());
								
								URL f_stream_url = PlayUtils.getMediaServerContentURL( f );

								if ( f_stream_url != null ){
									
									f_map.put(FIELD_FILES_CONTENT_URL, StaticUtils.adjustURL( host, f_stream_url ));
								}
							}
						}
					}
				}
			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}
	}
	
	private static final int RT_THUMBNAIL	= 0;
	
	private static String
	getThumbnailResourceURL(
		long	id )
	{
		Map map = new HashMap();
		
		map.put( "type", RT_THUMBNAIL );
		map.put( "id", id );
		
		String json = JSONUtils.encodeToJSON( map );
		
		return( "/vuze/resource?json=" + UrlUtils.encode( json ));
	}
	
	private boolean
	processResourceRequest(
		TrackerWebPageRequest		request,
		TrackerWebPageResponse		response,
		Map							request_json )
	
		throws IOException
	{
		int	type = ((Number)request_json.get( "type" )).intValue();
		
		if ( type == RT_THUMBNAIL ){
			
			long id = ((Number)request_json.get( "id" )).longValue();
			
			List<DownloadStub> list = getDownloads( id, false );
			
			if ( list == null || list.size() != 1 ){
				
				throw( new IOException( "Unknown download id: " + id ));
			}
			
			try{
				Download download = list.get(0).destubbify();
			
				Torrent torrent = download.getTorrent();
				
				byte[] data = PlatformTorrentUtils.getContentThumbnail( PluginCoreUtils.unwrap( torrent ));
				
					// TODO: handle image types
				
				response.setContentType( "image/jpeg" );
				
				response.getOutputStream().write( data );

			}catch( Throwable e ){
				
				throw( new IOException( "Failed to get thumbnail: " + StaticUtils.getCausesMesssages( e )));
			}
			
			return( true );
			
		}else{
			
			throw( new IOException( "Unknown resource type: " + type ));
		}
	}

	private void
	processVuzeLifecycle(
		Map<String,Object>	args,
		Map<String,Object>	result )
	
		throws IOException
	{
		checkUpdatePermissions();
		
		String	cmd = (String)args.get( "cmd" );
		
		if ( cmd == null ){
			
			throw( new IOException( "cmd missing" ));
		}
		
		try{
			switch (cmd) {
				case "status":

					synchronized (lifecycle_lock) {

						result.put("state", lifecycle_state);
					}

					break;
				case "close":

					synchronized (lifecycle_lock) {

						if (lifecycle_state < 2) {

							lifecycle_state = 2;

						} else {

							return;
						}
					}

					PluginManager.stopClient();

					break;
				case "restart":

					synchronized (lifecycle_lock) {

						if (lifecycle_state < 2) {

							lifecycle_state = 3;

						} else {

							return;
						}
					}

					PluginManager.restartClient();

					break;
				case "update-check":

					synchronized (lifecycle_lock) {

						if (lifecycle_state != 1) {

							throw (new IOException("update check can't currently be performed"));
						}

						if (update_in_progress) {

							throw (new IOException("update operation in progress"));
						}

						update_in_progress = true;
					}

					try {
						UpdateManager update_manager = plugin_interface.getUpdateManager();

						final UpdateCheckInstance checker = update_manager.createUpdateCheckInstance();

						final List<String> l_updates = new ArrayList<>();

						final AESemaphore sem = new AESemaphore("uc-wait");

						checker.addListener(
								new UpdateCheckInstanceListener() {
									@Override
									public void
									cancelled(
											UpdateCheckInstance instance) {
										sem.release();
									}

									@Override
									public void
									complete(
											UpdateCheckInstance instance) {
										try {
											Update[] updates = instance.getUpdates();

											for (Update update : updates) {
												
												l_updates.add("Update available for '" + update.getName() + "', new version = " + update.getNewVersion());
												
										/*
										String[]	descs = update.getDescription();
										
										for (int j=0;j<descs.length;j++){
											
											out.println( "\t" + descs[j] );
										}
										
										if ( update.isMandatory()){
											
											out.println( "**** This is a mandatory update, other updates can not proceed until this is performed ****" );
										}
										*/
											}

											// need to cancel this otherwise it sits there blocking other installer operations

											checker.cancel();

										} finally {

											sem.release();
										}
									}
								});

						checker.start();

						sem.reserve();

						result.put("updates", l_updates);

					} finally {

						synchronized (lifecycle_lock) {

							update_in_progress = false;
						}
					}
					break;
				case "update-apply":

					synchronized (lifecycle_lock) {

						if (lifecycle_state != 1) {

							throw (new IOException("update check can't currently be performed"));
						}

						if (update_in_progress) {

							throw (new IOException("update operation in progress"));
						}

						update_in_progress = true;
					}

					try {
						UpdateManager update_manager = plugin_interface.getUpdateManager();

						final UpdateCheckInstance checker = update_manager.createUpdateCheckInstance();

						final AESemaphore sem = new AESemaphore("uc-wait");

						final Throwable[] error = {null};
						final boolean[] restarting = {false};

						checker.addListener(
								new UpdateCheckInstanceListener() {
									@Override
									public void
									cancelled(
											UpdateCheckInstance instance) {
										sem.release();
									}

									@Override
									public void
									complete(
											UpdateCheckInstance instance) {
										Update[] updates = instance.getUpdates();

										try {

											for (Update update : updates) {

												for (ResourceDownloader rd : update.getDownloaders()) {

													rd.addListener(
															new ResourceDownloaderAdapter() {
																@Override
																public void
																reportActivity(
																		ResourceDownloader downloader,
																		String activity) {
																}

																@Override
																public void
																reportPercentComplete(
																		ResourceDownloader downloader,
																		int percentage) {
																}
															});

													rd.download();
												}
											}

											boolean restart_required = false;

											for (Update update : updates) {

												if (update.getRestartRequired() == Update.RESTART_REQUIRED_YES) {

													restart_required = true;
												}
											}

											if (restart_required) {

												synchronized (lifecycle_lock) {

													if (lifecycle_state < 2) {

														lifecycle_state = 3;

													} else {

														return;
													}
												}

												PluginManager.restartClient();

												restarting[0] = true;
											}
										} catch (Throwable e) {

											error[0] = e;

										} finally {

											sem.release();
										}
									}
								});

						checker.start();

						sem.reserve();

						if (error[0] != null) {

							throw (new IOException("Failed to apply updates: " + StaticUtils.getCausesMesssages(error[0])));
						}

						result.put("restarting", restarting[0]);

					} finally {

						synchronized (lifecycle_lock) {

							update_in_progress = false;
						}
					}
					break;
				default:

					throw (new IOException("Unknown cmd: " + cmd));
			}
		}catch( PluginException e ){
			
			throw( new IOException( "Lifecycle command failed: " + StaticUtils.getCausesMesssages(e)));
		}
	}
	
	private void
	processVuzePairing(
		Map<String,Object>	args,
		Map<String,Object>	result )
	
		throws IOException
	{
		checkUpdatePermissions();

		String	cmd = (String)args.get( "cmd" );

		if ( cmd == null ){
			
			throw( new IOException( "cmd missing" ));
		}

		PairingManager pm = PairingManagerFactory.getSingleton();

		switch (cmd) {
			case "status": {

				result.put("status", pm.getStatus());

				boolean enabled = pm.isEnabled();

				result.put("enabled", enabled);

				if (enabled) {

					result.put("access_code", pm.peekAccessCode());
				}

				boolean srp_enabled = pm.isSRPEnabled();

				result.put("srp_enabled", srp_enabled);

				if (srp_enabled) {

					result.put("srp_status", pm.getSRPStatus());
				}
				break;
			}
			case "set-enabled": {

				boolean enabled = (Boolean) args.get("enabled");

				if (enabled != pm.isEnabled()) {

					pm.setEnabled(enabled);
				}
				break;
			}
			case "set-srp-enabled": {

				boolean enabled = (Boolean) args.get("enabled");

				if (enabled != pm.isSRPEnabled()) {

					if (enabled) {

						String pw = (String) args.get("password");

						if (pw == null) {

							throw (new IOException("Password required when enabling SRP"));
						}

						pm.setSRPEnabled(true);

						pm.setSRPPassword(pw.toCharArray());

					} else {

						pm.setSRPEnabled(false);
					}
				}
				break;
			}
			default:

				throw (new IOException("Unknown cmd: " + cmd));
		}
	}
	
	protected static class
	PermissionDeniedException
		extends IOException
	{
		private static final long serialVersionUID = -344396020759893604L;		
	}

	private Number getTrackerID(TrackerPeerSource source) {
		return (long) ((source.getName().hashCode() << 4L) + source.getType());
	}

	//////////////////////////////////////////////////////////////////////////////

	@Override
	protected void log(String str) {
		// TODO Auto-generated method stub
		super.log(str);
	}

	@Override
	protected void log(String str, Throwable e) {
		super.log(str, e);
	}
}
