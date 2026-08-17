ConnectProxy
============
Small Go library to use CONNECT-speaking proxies standalone or with the
[proxy](golang.org/x/net/proxy/) library.

[![GoDoc](https://godoc.org/github.com/magisterquis/connectproxy?status.svg)](https://godoc.org/github.com/magisterquis/connectproxy)

Please see the godoc for more details.

This library is written to make connecting through proxies easier.  It
unashamedly steals from
https://gist.github.com/jim3ma/3750675f141669ac4702bc9deaf31c6b, but adds a
nice and simple interface.

For legal use only.

Domain Fronting
---------------
To make it easier to have a different SNI name and Host: header, a separate
SNI name may be specified when registering the proxy.  See the
`GeneratorWithConfig` documentation for more details.

Examples
--------
The godoc has a couple of examples.  Also, in the examples directory there is
an example program.
