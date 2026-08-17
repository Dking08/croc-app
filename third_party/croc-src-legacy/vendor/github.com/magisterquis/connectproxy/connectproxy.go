// Package connectproxy implements a proxy.Dialer which uses HTTP(s) CONNECT
// requests.
//
// It is heavily based on
// https://gist.github.com/jim3ma/3750675f141669ac4702bc9deaf31c6b and meant to
// compliment the proxy package (golang.org/x/net/proxy).
//
// Two URL schemes are supported: http and https.  These represent plaintext
// and TLS-wrapped connections to the proxy server, respectively.
//
// The proxy.Dialer returned by the package may either be used directly to make
// connections via a proxy which understands CONNECT request, or indirectly
// via dialer.RegisterDialerType.
//
// Direct use:
//     /* Make a proxy.Dialer */
//     d, err := connectproxy.New("https://proxyserver:4433", proxy.Direct)
//     if nil != err{
//             panic(err)
//     }
//
//     /* Connect through it */
//     c, err := d.Dial("tcp", "internalsite.com")
//     if nil != err {
//             log.Printf("Dial: %v", err)
//             return
//     }
//
//     /* Do something with c */
//
// Indirectly, via dialer.RegisterDialerType:
//     /* Register handlers for HTTP and HTTPS proxies */
//     proxy.RegisterDialerType("http", connectproxy.New)
//     proxy.RegisterDialerType("https", connectproxy.New)
//
//     /* Make a Dialer for a proxy */
//     u, err := url.Parse("https://proxyserver.com:4433")
//     if nil != err {
//             log.Fatalf("Parse: %v", err)
//     }
//     d, err := proxy.FromURL(u, proxy.Direct)
//     if nil != err {
//             log.Fatalf("Proxy: %v", err)
//     }
//
//     /* Connect through it */
//     c, err := d.Dial("tcp", "internalsite.com")
//     if nil != err {
//             log.Fatalf("Dial: %v", err)
//     }
//
//     /* Do something with c */
//
// It's also possible to make the TLS handshake with an HTTPS proxy server use
// a different name for SNI than the Host: header uses in the CONNECT request:
//     d, err := NewWithConfig(
//             "https://sneakyvhost.com:443",
//             proxy.Direct,
//             &connectproxy.Config{
//                     ServerName: "normalhoster.com",
//             },
//     )
//     if nil != err {
//             panic(err)
//     }
//
//     /* Use d.Dial(...) */
//
package connectproxy

/*
 * connectproxy.go
 * Implement a dialer which proxies via an HTTP CONNECT request
 * By J. Stuart McMurray
 * Created 20170821
 * Last Modified 20170821
 */

import (
	"bufio"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"time"

	"golang.org/x/net/proxy"
	"encoding/base64"
)

func init() {

}

// ErrorUnsupportedScheme is returned if a scheme other than "http" or
// "https" is used.
type ErrorUnsupportedScheme error

// ErrorConnectionTimeout is returned if the connection through the proxy
// server was not able to be made before the configured timeout expired.
type ErrorConnectionTimeout error

// Config allows various parameters to be configured.  It is used with
// NewWithConfig.  The config passed to NewWithConfig may be changed between
// requests.  If it is, the changes will affect all current and future
// invocations of the returned proxy.Dialer's Dial method.
type Config struct {
	// ServerName is the name to use in the TLS connection to (not through)
	// the proxy server if different from the host in the URL.
	// Specifically, this is used in the ServerName field of the
	// *tls.Config used in connections to TLS-speaking proxy servers.
	ServerName string

	// For proxy servers supporting TLS connections (to, not through),
	// skip TLS certificate validation.
	InsecureSkipVerify bool // Passed directly to tls.Dial

	// Header sets the headers in the initial HTTP CONNECT request.  See
	// the documentation for http.Request for more information.
	Header http.Header

	// DialTimeout is an optional timeout for connections through (not to)
	// the proxy server.
	DialTimeout time.Duration
}

// RegisterDialerFromURL is a convenience wrapper around
// proxy.RegisterDialerType, which registers the given URL as a for the schemes
// "http" and/or "https", as controlled by registerHTTP and registerHTTPS.  If
// both registerHTTP and registerHTTPS are false, RegisterDialerFromURL is a
// no-op.
func RegisterDialerFromURL(registerHTTP, registerHTTPS bool) {
	if registerHTTP {
		proxy.RegisterDialerType("http", New)
	}
	if registerHTTPS {
		proxy.RegisterDialerType("https", New)
	}
}

// connectDialer makes connections via an HTTP(s) server supporting the
// CONNECT verb.  It implements the proxy.Dialer interface.
type connectDialer struct {
	u       *url.URL
	forward proxy.Dialer
	config  *Config

	/* Auth from the url.  Avoids a function call */
	haveAuth bool
	username string
	password string
}

// New returns a proxy.Dialer given a URL specification and an underlying
// proxy.Dialer for it to make network requests.  New may be passed to
// proxy.RegisterDialerType for the schemes "http" and "https".  The
// convenience function RegisterDialerFromURL simplifies this.
func New(u *url.URL, forward proxy.Dialer) (proxy.Dialer, error) {
	return NewWithConfig(u, forward, nil)
}

// NewWithConfig is like New, but allows control over various options.
func NewWithConfig(u *url.URL, forward proxy.Dialer, config *Config) (proxy.Dialer, error) {
	/* Make sure we have an allowable scheme */
	if "http" != u.Scheme && "https" != u.Scheme {
		return nil, ErrorUnsupportedScheme(errors.New(
			"connectproxy: unsupported scheme " + u.Scheme,
		))
	}

	/* Need at least an empty config */
	if nil == config {
		config = &Config{}
	}

	/* To be returned */
	cd := &connectDialer{
		u:       u,
		forward: forward,
		config:  config,
	}

	/* Work out the TLS server name */
	if "" == cd.config.ServerName {
		h, _, err := net.SplitHostPort(u.Host)
		if nil != err && "missing port in address" == err.Error() {
			h = u.Host
		}
		cd.config.ServerName = h
	}

	/* Parse out auth */
	/* Below taken from https://gist.github.com/jim3ma/3750675f141669ac4702bc9deaf31c6b */
	if nil != u.User {
		cd.haveAuth = true
		cd.username = u.User.Username()
		cd.password, _ = u.User.Password()
	}

	return cd, nil
}

// GeneratorWithConfig is like NewWithConfig, but is suitable for passing to
// proxy.RegisterDialerType while maintaining configuration options.
//
// This is to enable registration of an http(s) proxy with options, e.g.:
//     proxy.RegisterDialerType("https", connectproxy.GeneratorWithConfig(
//             &connectproxy.Config{DialTimeout: 5 * time.Minute},
//     ))
func GeneratorWithConfig(config *Config) func(*url.URL, proxy.Dialer) (proxy.Dialer, error) {
	return func(u *url.URL, forward proxy.Dialer) (proxy.Dialer, error) {
		return NewWithConfig(u, forward, config)
	}
}

// Dial connects to the given address via the server.
func (cd *connectDialer) Dial(network, addr string) (net.Conn, error) {
	/* Connect to proxy server */
	nc, err := cd.forward.Dial("tcp", cd.u.Host)
	if nil != err {
		return nil, err
	}
	/* Upgrade to TLS if necessary */
	if "https" == cd.u.Scheme {
		nc = tls.Client(nc, &tls.Config{
			InsecureSkipVerify: cd.config.InsecureSkipVerify,
			ServerName:         cd.config.ServerName,
		})
	}

	/* The below adapted from https://gist.github.com/jim3ma/3750675f141669ac4702bc9deaf31c6b */

	/* Work out the URL to request */
	// HACK. http.ReadRequest also does this.
	reqURL, err := url.Parse("http://" + addr)
	if err != nil {
		nc.Close()
		return nil, err
	}
	reqURL.Scheme = ""
	req, err := http.NewRequest("CONNECT", reqURL.String(), nil)
	if err != nil {
		nc.Close()
		return nil, err
	}
	req.Close = false

	if (len(cd.config.Header) > 0) {
		req.Header = cd.config.Header
	}

	if cd.haveAuth {
		basicAuth := "Basic " + base64.StdEncoding.EncodeToString([]byte(cd.username + ":" + cd.password))	
		req.Header.Add("Proxy-Authorization", basicAuth)
	}

	/* Send the request */
	err = req.Write(nc)
	if err != nil {
		nc.Close()
		return nil, err
	}

	/* Timer to terminate long reads */
	var (
		connTOd   = false
		connected = make(chan string)
		to        = cd.config.DialTimeout
	)
	if 0 != to {
		go func() {
			select {
			case <-time.After(to):
				connTOd = true
				nc.Close()
			case <-connected:
			}
		}()
	}
	/* Wait for a response */
	resp, err := http.ReadResponse(bufio.NewReader(nc), req)
	close(connected)
	if nil != resp {
		resp.Body.Close()
	}
	if err != nil {
		nc.Close()
		if connTOd {
			return nil, ErrorConnectionTimeout(fmt.Errorf(
				"connectproxy: no connection to %q after %v",
				reqURL,
				to,
			))
		}
		return nil, err
	}
	/* Make sure we can proceed */
	if resp.StatusCode != http.StatusOK {
		nc.Close()
		return nil, fmt.Errorf(
			"connectproxy: non-OK status: %v",
			resp.Status,
		)
	}
	return nc, nil
}
