package reco

// Result is the value produced by a function node.
//
// A partial result is still a value and may be propagated. Err is deliberately
// part of Result rather than a second return value so compute functions keep
// all failure state explicit in the dataflow model.
type Result[T any] struct {
	Value     T
	Err       error
	IsPartial bool
}

// OK returns a complete successful Result.
func OK[T any](v T) Result[T] {
	return Result[T]{Value: v}
}

// Partial returns a partial Result with an error explaining what is incomplete.
func Partial[T any](v T, err error) Result[T] {
	return Result[T]{Value: v, Err: err, IsPartial: true}
}
