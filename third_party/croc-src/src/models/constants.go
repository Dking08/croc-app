package models

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path"
	"runtime"
	"strings"
	"sync"
	"time"

	log "github.com/schollz/croc/v11/src/logger"
	"github.com/schollz/croc/v11/src/utils"
)

// TCP_BUFFER_SIZE is the maximum packet size
const TCP_BUFFER_SIZE = 1024 * 64

// Default relay addresses retain their hostnames until dialing so proxies can
// resolve them remotely. DEFAULT_RELAY can be overridden using --relay.
var (
	DEFAULT_RELAY      = "croc.schollz.com:9009"
	DEFAULT_RELAY6     = "croc6.schollz.com:9009"
	DEFAULT_PORT       = "9009"
	DEFAULT_PASSPHRASE = "pass123"
	INTERNAL_DNS       = false
)

// publicDNS are servers to be queried if a local lookup fails
var publicDNS = []string{
	"1.0.0.1",                // Cloudflare
	"1.1.1.1",                // Cloudflare
	"[2606:4700:4700::1111]", // Cloudflare
	"[2606:4700:4700::1001]", // Cloudflare
	"8.8.4.4",                // Google
	"8.8.8.8",                // Google
	"[2001:4860:4860::8844]", // Google
	"[2001:4860:4860::8888]", // Google
	"9.9.9.9",                // Quad9
	"149.112.112.112",        // Quad9
	"[2620:fe::fe]",          // Quad9
	"[2620:fe::fe:9]",        // Quad9
	"8.26.56.26",             // Comodo
	"8.20.247.20",            // Comodo
	"208.67.220.220",         // Cisco OpenDNS
	"208.67.222.222",         // Cisco OpenDNS
	"[2620:119:35::35]",      // Cisco OpenDNS
	"[2620:119:53::53]",      // Cisco OpenDNS
}

var initDNSOnce sync.Once

// GetDNSServers returns DNS server addresses (host:53) to use for name resolution.
// If the CROC_DNS environment variable is set (e.g. from Android ConnectivityManager),
// those servers are queried first, followed by public resolvers.
func GetDNSServers() []string {
	var servers []string
	if env := os.Getenv("CROC_DNS"); env != "" {
		for _, s := range strings.Split(env, ",") {
			s = strings.TrimSpace(s)
			if s != "" {
				if !strings.Contains(s, ":") {
					s = s + ":53"
				} else if strings.Count(s, ":") > 1 && !strings.Contains(s, "[") {
					s = "[" + s + "]:53"
				}
				servers = append(servers, s)
			}
		}
	}
	servers = append(servers, "1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53", "1.0.0.1:53", "8.8.4.4:53")
	return servers
}

// ResolveHostFallback resolves a hostname by attempting DNS lookups against
// configured system DNS (CROC_DNS) and public resolvers sequentially.
func ResolveHostFallback(ctx context.Context, host string) ([]string, error) {
	servers := GetDNSServers()
	var lastErr error
	for _, s := range servers {
		r := &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
				d := net.Dialer{Timeout: 3 * time.Second}
				return d.DialContext(ctx, "udp", s)
			},
		}
		lookupCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		ips, err := r.LookupHost(lookupCtx, host)
		cancel()
		if err == nil && len(ips) > 0 {
			return ips, nil
		}
		var dnsErr *net.DNSError
		if errors.As(err, &dnsErr) && dnsErr.IsNotFound {
			return nil, err
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no DNS servers available to resolve %s", host)
	}
	return nil, lastErr
}

// FallbackDialContext wraps a dialer function with fallback DNS resolution.
// It tries normal dialing first; if that fails with a DNSError (typical on Android
// where /etc/resolv.conf does not exist for pure-Go binaries), it resolves the host
// using ResolveHostFallback and dials the resulting IP.
func FallbackDialContext(origDial func(context.Context, string, string) (net.Conn, error)) func(context.Context, string, string) (net.Conn, error) {
	baseDialer := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
	if origDial == nil {
		origDial = baseDialer.DialContext
	}
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		conn, err := origDial(ctx, network, addr)
		if err == nil {
			return conn, nil
		}

		var dnsErr *net.DNSError
		if !errors.As(err, &dnsErr) {
			return nil, err
		}

		host, port, splitErr := net.SplitHostPort(addr)
		if splitErr != nil {
			return nil, err
		}

		ips, lookupErr := ResolveHostFallback(ctx, host)
		if lookupErr != nil || len(ips) == 0 {
			return nil, err
		}

		for _, ip := range ips {
			target := net.JoinHostPort(ip, port)
			c, dialErr := baseDialer.DialContext(ctx, network, target)
			if dialErr == nil {
				return c, nil
			}
		}
		return nil, err
	}
}

// InitDNS initializes DNS configuration for Android and internal DNS mode.
func InitDNS() {
	if runtime.GOOS == "android" || os.Getenv("CROC_DNS") != "" || INTERNAL_DNS {
		INTERNAL_DNS = true

		initDNSOnce.Do(func() {
			if t, ok := http.DefaultTransport.(*http.Transport); ok {
				t.DialContext = FallbackDialContext(t.DialContext)
			}
		})
	}
}

func getConfigFile(requireValidPath bool) (fname string, err error) {
	configFile, err := utils.GetConfigDir(requireValidPath)
	if err != nil {
		return
	}
	fname = path.Join(configFile, "internal-dns")
	return
}

func init() {
	log.SetLevel("info")
	log.SetOutput(os.Stderr)
	doRemember := false
	for _, flag := range os.Args {
		if flag == "--internal-dns" {
			INTERNAL_DNS = true
			break
		}
		if flag == "--remember" {
			doRemember = true
		}
	}
	if doRemember {
		// save in config file
		fname, err := getConfigFile(true)
		if err == nil {
			f, _ := os.Create(fname)
			f.Close()
		}
	}
	if !INTERNAL_DNS {
		fname, err := getConfigFile(false)
		if err == nil {
			INTERNAL_DNS = utils.Exists(fname)
		}
	}
	InitDNS()
	log.Trace("Using internal DNS: ", INTERNAL_DNS)
}

// ResolveRelayAddress applies the optional built-in DNS resolver to a direct
// connection. Proxy connections must keep the hostname for proxy-side DNS.
func ResolveRelayAddress(ctx context.Context, address string) (string, error) {
	if !INTERNAL_DNS {
		return address, nil
	}
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return "", err
	}
	if net.ParseIP(host) != nil {
		return address, nil
	}
	ip, err := lookup(ctx, host)
	if err != nil {
		return "", err
	}
	return net.JoinHostPort(ip, port), nil
}

// Resolve a hostname to an IP address using DNS.
func lookup(ctx context.Context, address string) (ipaddress string, err error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	if !INTERNAL_DNS {
		log.Tracef("Using local DNS to resolve %s", address)
		return localLookupIPContext(ctx, address)
	}
	ips, err := ResolveHostFallback(ctx, address)
	if err == nil && len(ips) > 0 {
		log.Tracef("Resolved %s to %s using fallback DNS", address, ips[0])
		return ips[0], nil
	}
	err = fmt.Errorf("failed to resolve %s: all DNS servers exhausted (%w)", address, err)
	return
}

// localLookupIP returns a host's IP address using the local DNS configuration.
func localLookupIP(address string) (ipaddress string, err error) {
	return localLookupIPContext(context.Background(), address)
}

func localLookupIPContext(ctx context.Context, address string) (ipaddress string, err error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	r := &net.Resolver{}

	// Use the context with timeout in the LookupHost function
	ip, err := r.LookupHost(ctx, address)
	if err != nil {
		return
	}
	ipaddress = ip[0]
	return
}

// remoteLookupIP returns a host's IP address based on a remote DNS server.
func remoteLookupIP(address, dns string) (ipaddress string, err error) {
	return remoteLookupIPContext(context.Background(), address, dns)
}

func remoteLookupIPContext(ctx context.Context, address, dns string) (ipaddress string, err error) {
	ctx, cancel := context.WithTimeout(ctx, 800*time.Millisecond)
	defer cancel()

	r := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := new(net.Dialer)
			return d.DialContext(ctx, network, dns+":53")
		},
	}
	ip, err := r.LookupHost(ctx, address)
	if err != nil {
		return
	}
	ipaddress = ip[0]
	return
}
