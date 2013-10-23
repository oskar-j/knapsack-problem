knapsack-problem
===================

### Puzzle solver for a rucksack problem

#### Problem definition

Mathematically the 0-1-knapsack problem can be formulated as:

Let there be `n` items, `x_1` to `x_n` where `x_i` has a value `v_i` and weight `w_i`. 
The maximum weight that we can carry in the bag is `W`. 
It is common to assume that all values and weights are nonnegative. 
To simplify the representation, we also assume that the items are listed in increasing order of weight.
Maximize the sum of the values of the items in the knapsack 
so that the sum of the weights must be less than the knapsack's capacity.
