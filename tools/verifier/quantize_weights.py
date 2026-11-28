"""Kvantizace JEN VAH (aktivace zůstávají fp32 – dynamická kvantizace aktivací tento model rozbíjí, viz protokol):
 - tabulka embeddingů → int8 po řádcích; dekvantuje se až po výběru řádků (Gather → Cast → Mul) → v paměti zůstane int8,
 - váhy MatMul (2D) → int8 po výstupních kanálech + DequantizeLinear (ORT je při načtení může složit do fp32).
  python quantize_weights.py vstup.onnx vystup.onnx"""
import sys, numpy as np, onnx
from onnx import numpy_helper, helper, TensorProto
src, dst = sys.argv[1], sys.argv[2]
EMB_ONLY = "--emb-only" in sys.argv
m = onnx.load(src); g = m.graph
inits = {i.name: i for i in g.initializer}
def q_rows(w, axis):
    s = np.abs(w).max(axis=axis, keepdims=True) / 127.0; s[s == 0] = 1.0
    return np.clip(np.round(w / s), -127, 127).astype(np.int8), s.astype(np.float32)
new_nodes = []; drop = set(); add = []
for n in g.node:
    if n.op_type == "Gather" and n.input[0] in inits and numpy_helper.to_array(inits[n.input[0]]).ndim == 2 and numpy_helper.to_array(inits[n.input[0]]).shape[0] > 1000:
        name = n.input[0]; w = numpy_helper.to_array(inits[name]); q, s = q_rows(w, 1)
        add += [numpy_helper.from_array(q, name + "_q"), numpy_helper.from_array(s.reshape(-1), name + "_s")]; drop.add(name)
        o = n.output[0]
        new_nodes += [helper.make_node("Gather", [name + "_q", n.input[1]], [o + "_gq"], axis=0),
                      helper.make_node("Cast", [o + "_gq"], [o + "_gf"], to=TensorProto.FLOAT),
                      helper.make_node("Gather", [name + "_s", n.input[1]], [o + "_gs"], axis=0),
                      helper.make_node("Unsqueeze", [o + "_gs", o + "_ax"], [o + "_gs2"]),
                      helper.make_node("Mul", [o + "_gf", o + "_gs2"], [o])]
        add.append(numpy_helper.from_array(np.array([-1], dtype=np.int64), o + "_ax"))
        continue
    if not EMB_ONLY and n.op_type == "MatMul" and n.input[1] in inits and numpy_helper.to_array(inits[n.input[1]]).ndim == 2:
        name = n.input[1]; w = numpy_helper.to_array(inits[name])
        if name not in drop:
            q, s = q_rows(w, 0); add += [numpy_helper.from_array(q, name + "_q"), numpy_helper.from_array(s.reshape(-1), name + "_s"), numpy_helper.from_array(np.zeros(w.shape[1], np.int8), name + "_z")]
            new_nodes.append(helper.make_node("DequantizeLinear", [name + "_q", name + "_s", name + "_z"], [name + "_dq"], axis=1)); drop.add(name)
        new_nodes.append(helper.make_node("MatMul", [n.input[0], name + "_dq"], list(n.output), name=n.name)); continue
    new_nodes.append(n)
keep = [i for i in g.initializer if i.name not in drop]
del g.initializer[:]; g.initializer.extend(keep + add)
del g.node[:]; g.node.extend(new_nodes)
onnx.checker.check_model(m); onnx.save(m, dst)
print("uloženo", dst)
