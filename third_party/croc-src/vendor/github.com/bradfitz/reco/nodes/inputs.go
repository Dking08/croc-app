package nodes

import "github.com/bradfitz/reco"

// countInputs indexes a variadic input list once, at declaration time. The
// immutable index preserves contribution multiplicities even though
// ChangedInputs yields each distinct declared handle only once.
func countInputs[T any](inputs []reco.Node[T]) ([]reco.Dependency, map[reco.Node[T]]int) {
	counts := make(map[reco.Node[T]]int, len(inputs))
	var deps []reco.Dependency
	for _, input := range inputs {
		if counts[input] == 0 {
			deps = append(deps, input)
		}
		counts[input]++
	}
	return deps, counts
}
