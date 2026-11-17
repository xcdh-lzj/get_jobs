export type AnalysisChartProps = {
  type: "pie" | "bar" | "line"
  labels: string[]
  data: number[]
  title?: string
  color?: string
  colors?: string[]
}

function sameValues<T>(previous: T[] | undefined, next: T[] | undefined): boolean {
  if (previous === next) return true
  if (!previous || !next || previous.length !== next.length) return false
  return previous.every((value, index) => Object.is(value, next[index]))
}

// 父页面轮询、输入筛选条件和翻页都可能生成新数组；只有内容改变才重绘图表。
export function sameAnalysisChart(previous: AnalysisChartProps, next: AnalysisChartProps): boolean {
  return previous.type === next.type
    && previous.title === next.title
    && (previous.color ?? "#3b82f6") === (next.color ?? "#3b82f6")
    && sameValues(previous.labels, next.labels)
    && sameValues(previous.data, next.data)
    && sameValues(previous.colors, next.colors)
}
