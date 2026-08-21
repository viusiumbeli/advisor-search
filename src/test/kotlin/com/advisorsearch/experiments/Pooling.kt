package com.advisorsearch.experiments

/**
 * How a candidate model turns token vectors into one sentence vector. Each checkpoint documents
 * which it was trained with, and using the wrong one silently degrades every similarity.
 */
internal enum class Pooling { MEAN, CLS }
