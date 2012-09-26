/**
 * 
 */
package org.archive.crawler.hadoop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.mortbay.util.ajax.JSON;

/**
 * Searches items in given collection with private custom web app that indexes individual collection
 * for faster look up.
 * 
 * @author kenji
 *
 */
public class CollectionIndexItemSearcher implements ItemSearcher {
  
  private static final Log LOG = LogFactory.getLog(CollectionIndexItemSearcher.class);

  protected PetaboxFileSystem fs;
  protected URI fsUri;
  
  String serviceUri = "http://crawl400.us.archive.org/crawling/wide/getitems.py";
  
  protected int maxRetries = 10;
  protected int retryDelay = 2000; // milliseconds

  /* (non-Javadoc)
   * @see org.archive.crawler.hadoop.ItemSearcher#initialize(org.archive.crawler.hadoop.PetaboxFileSystem, java.net.URI, org.apache.hadoop.conf.Configuration)
   */
  @Override
  public void initialize(PetaboxFileSystem fs, URI fsUri, Configuration conf) {
    this.fs = fs;
    this.fsUri = fsUri;
    
    serviceUri = conf.get(CollectionIndexItemSearcher.class.getName()+".serviceUri", serviceUri);
  }

  protected URI buildSearchURI(String itemid) throws URISyntaxException {
    return URI.create(serviceUri + "/" + itemid);
  }
  
  /* (non-Javadoc)
   * @see org.archive.crawler.hadoop.ItemSearcher#searchItems(java.lang.String)
   */
  @Override
  public FileStatus[] searchItems(String itemid) throws IOException {
    List<FileStatus> result = null;
    URI uri;
    try {
      uri = buildSearchURI(itemid);
      LOG.debug("search uri=" + uri);
    } catch (URISyntaxException ex) {
      throw new IOException("failed to build URI for itemid=" + itemid, ex);
    }
    HttpClient client = fs.getHttpClient();
    HttpGet get = fs.createHttpGet(uri);
    HttpEntity entity = null;
    int retries = 0;
    do {
      if (retries > 0) {
	if (retries > maxRetries) {
	  throw new IOException(uri + ": retry exhausted, giving up.");
	}
	try {
	  Thread.sleep(retryDelay);
	} catch (InterruptedException ex) {
	}
      }
      HttpResponse resp;
      try {
	resp = client.execute(get);
      } catch (IOException ex) {
	LOG.warn("connection to " + uri + " failed", ex);
	++retries;
	continue;
      }
      StatusLine st = resp.getStatusLine();
      entity = resp.getEntity();
      switch (st.getStatusCode()) {
      case 200:
	if (retries > 0) {
	  LOG.info(uri + ": succeeded after " + retries + " retry(ies)");
	}
	// it appears search engine often fails to return JSON formatted output despite
	// status code 200. detect it here.
	Reader reader = new InputStreamReader(entity.getContent(), "UTF-8");
	BufferedReader lines = new BufferedReader(reader);
	result = new ArrayList<FileStatus>();
	String line;
	int ln = 0;
	try {
	  while ((line = lines.readLine()) != null) {
	    ln++;
	    @SuppressWarnings("unchecked")
	    Map<String, Object> jo = (Map<String, Object>)JSON.parse(line);
	    String iid = (String)jo.get("id");
	    long mtime = (Long)jo.get("m");
	    Path qf = new Path(fsUri.toString(), "/" + iid);
	    LOG.debug("collection:" + itemid + " qf=" + qf);
	    FileStatus fst = new FileStatus(0, true, 2, 4096, mtime, qf);
	    result.add(fst);
	  }
	} catch (IOException ex) {
	  LOG.warn(uri + "error reading response", ex);
	  ++retries;
	  continue;
	} catch (IllegalStateException ex) {
	  // JSON.parse throws this for parse error.
	  LOG.warn(uri + ": JSON.parse failed at line " + ln, ex);
	  ++retries;
	  continue;
	} finally {
	  lines.close();
	}
	break;
      case 502:
      case 503:
      case 504:
	if (entity != null)
	  entity.getContent().close();
	++retries;
	LOG.warn(uri + " failed " + st.getStatusCode() + " "
	    + st.getReasonPhrase() + ", retry " + retries);
	entity = null;
	continue;
      default:
	entity.getContent().close();
	throw new IOException(st.getStatusCode() + " " + st.getReasonPhrase());
      }
    } while (result == null);
    LOG.info(String.format("searchItems(collection=%s): returning %d items", itemid, result.size()));
    return result.toArray(new FileStatus[result.size()]);
  }

}
