import dagre from "@dagrejs/dagre";
import { MarkerType, Position } from "@vue-flow/core";

export const PLAN_FLOW_NODE_WIDTH = 300;
export const PLAN_FLOW_NODE_HEIGHT = 126;

function nodeId(node, index) {
  return String(node?.id || `step-${node?.stepId || index + 1}`);
}

function edgeEndpoint(edge, side) {
  if (side === "source") return edge?.source || edge?.from || (edge?.fromStepId != null ? `step-${edge.fromStepId}` : "");
  return edge?.target || edge?.to || (edge?.toStepId != null ? `step-${edge.toStepId}` : "");
}

export function layoutPlanDag(rawNodes = [], rawEdges = []) {
  const nodes = Array.isArray(rawNodes) ? rawNodes : [];
  const edges = Array.isArray(rawEdges) ? rawEdges : [];
  const graph = new dagre.graphlib.Graph({ multigraph: true });
  graph.setDefaultEdgeLabel(() => ({}));
  graph.setGraph({
    rankdir: "LR",
    align: "UL",
    ranksep: 105,
    nodesep: 54,
    edgesep: 28,
    marginx: 28,
    marginy: 28
  });

  const ids = new Set();
  nodes.forEach((node, index) => {
    const id = nodeId(node, index);
    ids.add(id);
    graph.setNode(id, { width: PLAN_FLOW_NODE_WIDTH, height: PLAN_FLOW_NODE_HEIGHT });
  });
  const normalizedEdges = edges.map((edge, index) => {
    const source = String(edgeEndpoint(edge, "source"));
    const target = String(edgeEndpoint(edge, "target"));
    const id = String(edge?.id || `edge-${source}-${target}-${index}`);
    if (ids.has(source) && ids.has(target)) graph.setEdge(source, target, {}, id);
    return { edge, id, source, target };
  }).filter(({ source, target }) => ids.has(source) && ids.has(target));

  dagre.layout(graph);
  return {
    nodes: nodes.map((node, index) => {
      const id = nodeId(node, index);
      const point = graph.node(id) || { x: 0, y: 0 };
      return {
        id,
        type: "plan",
        position: {
          x: point.x - PLAN_FLOW_NODE_WIDTH / 2,
          y: point.y - PLAN_FLOW_NODE_HEIGHT / 2
        },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        data: { ...node, id }
      };
    }),
    edges: normalizedEdges.map(({ edge, id, source, target }) => ({
      id,
      source,
      target,
      type: "smoothstep",
      label: String(edge?.label || edge?.kind || edge?.type || ""),
      markerEnd: MarkerType.ArrowClosed,
      data: edge
    }))
  };
}
