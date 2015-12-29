package io.ably.lib.http;

import io.ably.lib.http.Http.BodyHandler;
import io.ably.lib.http.Http.ResponseHandler;
import io.ably.lib.types.AblyException;
import io.ably.lib.types.PaginatedResult;
import io.ably.lib.types.Param;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An object that encapsulates parameters of a REST query with a paginated response
 *
 * @param <T> the body response type.
 */
public class PaginatedQuery<T> implements ResponseHandler<PaginatedResult<T>> {

	/**
	 * Construct a PaginatedQuery
	 * 
	 * @param http. the http instance
	 * @param path. the path of the resource being queried
	 * @param headers. headers to pass into the first and all relative queries
	 * @param params. params to pass into the initial query
	 * @param bodyHandler. handler to parse response bodies for first and all relative queries
	 */
	public PaginatedQuery(Http http, String path, Param[] headers, Param[] params, BodyHandler<T> bodyHandler) {
		this.http = http;
		this.path = path;
		this.headers = headers;
		this.params = params;
		this.bodyHandler = bodyHandler;
	}

	/**
	 * Get the result of the first query
	 * @return A PaginatedResult<T> giving the first page of results
	 * together with any available links to related results pages.
	 * @throws AblyException
	 */
	public PaginatedResult<T> get() throws AblyException {
		return http.get(path, headers, params, this);
	}

	/**
	 * A private class encapsulating the result of a single page response
	 *
	 */
	public class ResultPage implements PaginatedResult<T> {
		private T[] contents;

		private ResultPage(T[] contents, Collection<String> linkHeaders) throws AblyException {
			this.contents = contents;

			if(linkHeaders != null) {
				HashMap<String, String> links = parseLinks(linkHeaders);
				relFirst = links.get("first");
				relCurrent = links.get("current");
				relNext = links.get("next");
			}
		}

		@Override
		public T[] items() { return contents; }

		@Override
		public PaginatedResult<T> first() throws AblyException { return getRel(relFirst); }

		@Override
		public PaginatedResult<T> current() throws AblyException { return getRel(relCurrent); }

		@Override
		public PaginatedResult<T> next() throws AblyException { return getRel(relNext); }

		private PaginatedResult<T> getRel(String linkUrl) throws AblyException {
			if(linkUrl == null) return null;
			/* we're expecting the format to be ./path-component?name=value&name=value... */
			Matcher urlMatch = urlPattern.matcher(linkUrl);
			if(urlMatch.matches()) {
				String[] paramSpecs = urlMatch.group(2).split("&");
				Param[] params = new Param[paramSpecs.length];
				try {
					for(int i = 0; i < paramSpecs.length; i++) {
						String[] split = paramSpecs[i].split("=");
						params[i] = new Param(split[0], URLDecoder.decode(split[1], "UTF-8"));
					}
				} catch(UnsupportedEncodingException uee) {}
				return http.get(path, headers, params, PaginatedQuery.this);
			}
			throw new AblyException("Unexpected link URL format", 500, 50000);
		}
	
		private String relFirst, relCurrent, relNext;

		@Override
		public boolean hasFirst() { return relFirst != null; }

		@Override
		public boolean hasCurrent() { return relCurrent != null; }

		@Override
		public boolean hasNext() { return relNext != null; }
	}

	@Override
	public ResultPage handleResponse(int statusCode, String contentType, Collection<String> linkHeaders, byte[] body) throws AblyException {
		T[] responseContents = bodyHandler.handleResponseBody(contentType, body);
		return new ResultPage(responseContents, linkHeaders);
	}

	/****************
	 * internal
	 ****************/

	private static Pattern linkPattern = Pattern.compile("\\s*<(.*)>;\\s*rel=\"(.*)\"");
	private static Pattern urlPattern = Pattern.compile("\\./(.*)\\?(.*)");

	private static HashMap<String, String> parseLinks(Collection<String> linkHeaders) {
		HashMap<String, String> result = new HashMap<String, String>();
		for(String link : linkHeaders) {
			/* we're expecting the format to be <url>; rel="first current ..." */
			Matcher linkMatch = linkPattern.matcher(link);
			if(linkMatch.matches()) {
				String linkUrl = linkMatch.group(1);
				for (String linkRel : linkMatch.group(2).toLowerCase(Locale.ENGLISH).split("\\s"))
					result.put(linkRel, linkUrl);
			}
		}
		return result;
	}

	private Http http;
	private String path;
	private Param[] headers;
	private Param[] params;
	private BodyHandler<T> bodyHandler;
}
