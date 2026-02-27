import py2neo
from py2neo import Graph, Node, Relationship
import os
import sys
import pygraphviz as pgv
import pyverilog
from pyverilog.vparser.parser import parse
from pyverilog.ast_code_generator.codegen import ASTCodeGenerator
from pyverilog.dataflow.dataflow_analyzer import VerilogDataflowAnalyzer
from pyverilog.dataflow.optimizer import VerilogDataflowOptimizer
from pyverilog.dataflow.graphgen import VerilogGraphGenerator
from transformers import AutoTokenizer, AutoModel
import torch

uri = "bolt://localhost:7687"
user = "neo4j"
password = "neo4j"

checkpoint = "Salesforce/codet5p-110m-embedding"
device = "cpu"
tokenizer = AutoTokenizer.from_pretrained(checkpoint,trust_remote_code=True)
model = AutoModel.from_pretrained(checkpoint, trust_remote_code=True).to(device)

def encode_code(code_text):
    inputs = tokenizer(code_text, return_tensors="pt")["input_ids"].to(device)
    embedding = model(inputs)[0]
    return embedding

def parse_verilog(file_path):
    try:
        with open(file_path, 'rb') as f:
            content_bytes = f.read()
            
        try:
            content = content_bytes.decode()
        except UnicodeDecodeError:
            content = content_bytes.decode('utf-8')
            
        temp_file = file_path + '.temp'
        with open(temp_file, 'w') as f:
            f.write(content)
            
        try:
            ast, directives = parse([temp_file])
            return ast
        finally:
            if os.path.exists(temp_file):
                os.remove(temp_file)
                
    except Exception as e:
        print(f"Parse error: {str(e)}")
        raise

def store_verilog_graph(file_path):
    try:
        graph = Graph(uri, auth=(user, password))
        
        ast = parse_verilog(file_path)
        all_blocks, signal_blocks = find_blocks_and_signals(ast)
        dataflow_info = analyze_with_dataflow(file_path, signal_blocks)
        
        top_module = get_top_module_name(ast)
        
        with open(file_path, 'rb') as f:
            content_bytes = f.read()
        try:
            content = content_bytes.decode()
        except UnicodeDecodeError:
            content = content_bytes.decode('utf-8')
            
        code_embedding = encode_code(content).tolist()
        module_node = Node("Module",
                         name=top_module,
                         code=content,
                         code_embedding=code_embedding,
                         file_path=file_path)
        graph.create(module_node)
        
        block_nodes = {}
        instance_blocks = {}
        for block_id, block_info in all_blocks.items():
            if isinstance(block_info, tuple) and len(block_info) >= 2:
                block_type = block_info[0]
                block_code = block_info[1]
                
                if block_type == 'Instance':
                    instance_info = block_info[2]
                    code_embedding = encode_code(block_code).tolist()
                    
                    port_connections_str = ";".join([
                        f"{port}:{signal}" 
                        for port, signal in instance_info['port_connections'].items()
                    ])
                    
                    block_node = Node("Block",
                                   id=str(block_id),
                                   type=block_type,
                                   code=block_code,
                                   code_embedding=code_embedding,
                                   instance_name=instance_info['instance_name'],
                                   module_name=instance_info['module_name'],
                                   port_connections_str=port_connections_str)
                    
                    graph.create(block_node)
                    block_nodes[block_id] = block_node
                    instance_blocks[block_id] = instance_info
                    graph.create(Relationship(module_node, "CONTAINS", block_node))
                else:
                    if block_code:
                        code_embedding = encode_code(block_code).tolist()
                        block_node = Node("Block",
                                       id=str(block_id),
                                       type=block_type,
                                       code=block_code,
                                       code_embedding=code_embedding)
                        graph.create(block_node)
                        block_nodes[block_id] = block_node
                        graph.create(Relationship(module_node, "CONTAINS", block_node))
        
        for block_id, instance_info in instance_blocks.items():
            module_name = instance_info['module_name']
            target_module = graph.run(
                "MATCH (m:Module {name: $name}) RETURN m",
                name=module_name
            ).evaluate()
            
            if target_module:
                port_connections_str = ";".join([
                    f"{port}:{signal}" 
                    for port, signal in instance_info['port_connections'].items()
                ])
                
                graph.create(Relationship(
                    block_nodes[block_id],
                    "INSTANTIATES",
                    target_module,
                    port_connections=port_connections_str
                ))
        
        signal_nodes = {}
        all_signals = set()
        
        for signal_name, info in dataflow_info.items():
            if signal_name and isinstance(signal_name, str):
                all_signals.add(signal_name)
                for flow in info.get('dataflow', []):
                    if 'all_signals' in flow:
                        for src_signal in flow['all_signals']:
                            src_name = str(src_signal).split('.')[-1]
                            if src_name:
                                all_signals.add(src_name)
        
        for signal_name in all_signals:
            signal_node = Node("Signal", name=signal_name)
            graph.create(signal_node)
            signal_nodes[signal_name] = signal_node
            
            context_blocks = [b['code'] for b in graph.run(
                "MATCH (b:Block)-[:CONTAINS]->(s:Signal {name: $name}) RETURN b.code as code",
                name=signal_name
            ).data()]
            if context_blocks:
                context = f"Signal: {signal_name}\nContext:\n" + "\n".join(context_blocks)
                context_embedding = encode_code(context).tolist()
                signal_node['context'] = context
                signal_node['context_embedding'] = context_embedding
                graph.push(signal_node)
        
        for signal_name, info in dataflow_info.items():
            if signal_name in signal_nodes:
                related_blocks = set()
                for block_type, block_id in info.get('blocks', []):
                    related_blocks.add(block_id)
                for flow in info.get('dataflow', []):
                    if 'block_id' in flow:
                        related_blocks.add(flow['block_id'])
                for block_id in related_blocks:
                    if block_id in block_nodes:
                        graph.create(Relationship(block_nodes[block_id], "CONTAINS", signal_nodes[signal_name]))

                for flow in info.get('dataflow', []):
                    if 'all_signals' in flow:
                        for src_signal in flow['all_signals']:
                            src_name = str(src_signal).split('.')[-1]
                            if src_name in signal_nodes and src_name != signal_name:
                                flow_type = flow.get('always_type', 'combinational')
                                rel_props = {
                                    'type': flow_type,
                                    'assignment': flow.get('assignment_type', 'unknown')
                                }
                                
                                if flow_type == 'clockedge':
                                    rel_props.update({
                                        'clock_edge': flow.get('clock_edge'),
                                        'clock_name': flow.get('clock_name')
                                    })
                                
                                graph.create(Relationship(
                                    signal_nodes[src_name], 
                                    "FLOWS_TO", 
                                    signal_nodes[signal_name],
                                    **rel_props
                                ))

        ast = parse_verilog(file_path)
        top_module = get_top_module_name(ast)
        analyzer = VerilogDataflowAnalyzer(
            [file_path],
            top_module,
            [],
            [],
            file_path
        )
        analyzer.generate()
        terms = analyzer.getTerms()
        binddict = analyzer.getBinddict()

        dfg = analyze_dataflow(file_path, top_module)
        dfg_nodes = {}  
        
        for node in dfg.nodes():
            node_name = str(node)
            
            matching_term = find_matching_term(node_name, terms, top_module)
            if matching_term:
                term_name = str(matching_term).split('.')[-1]
                if term_name in signal_nodes:
                    dfg_nodes[node_name] = signal_nodes[term_name]
                    continue
            

            dfg_node = Node("Temp", name=node_name, type=node.attr.get('label','temp'))
            
            graph.create(dfg_node)
            dfg_nodes[node_name] = dfg_node
        
        for edge in dfg.edges():
            source_name = str(edge[1])
            target_name = str(edge[0])
            if source_name in dfg_nodes and target_name in dfg_nodes:
                source_node = dfg_nodes[source_name]
                target_node = dfg_nodes[target_name]
                edge_type = edge.attr.get('label', "FLOWS_TO")
                if not edge_type:
                    edge_type = "FLOWS_TO"

                graph.create(Relationship(source_node, edge_type, target_node))
        
    except Exception as e:
        print(f"Error processing: {str(e)}")
        raise

def find_blocks_and_signals(ast):
    all_blocks = {}
    signal_blocks = {}
    codegen = ASTCodeGenerator()
    
    def traverse(node, current_block):
        if hasattr(node, 'children'):
            for child in node.children():
                traverse(child, current_block)
        
        if isinstance(node, (pyverilog.vparser.ast.Input,
                           pyverilog.vparser.ast.Output,
                           pyverilog.vparser.ast.Inout,
                           pyverilog.vparser.ast.Reg,
                           pyverilog.vparser.ast.Wire,
                           pyverilog.vparser.ast.Integer,
                           pyverilog.vparser.ast.Identifier,
                           )):
            
            if node.name not in signal_blocks:
                signal_blocks[node.name] = []
            block_ref = (current_block[0], current_block[1])
            if block_ref not in signal_blocks[node.name]:
                signal_blocks[node.name].append(block_ref)
    
    def traverse_blocks(node):
        if hasattr(node, 'children'):
            for child in node.children():
                
                if isinstance(child, (pyverilog.vparser.ast.Assign, 
                                   pyverilog.vparser.ast.Always,
                                   pyverilog.vparser.ast.Initial,
                                   pyverilog.vparser.ast.Function,
                                   pyverilog.vparser.ast.Task,
                                   pyverilog.vparser.ast.Decl,
                                   pyverilog.vparser.ast.Paramlist,
                                   pyverilog.vparser.ast.Portlist)):
                    block_id = id(child)
                    block_type = child.__class__.__name__
                    block_code = codegen.visit(child)
                    
                    all_blocks[block_id] = (block_type, block_code)
                    block_info = (block_type, block_id, block_code)
                    traverse(child, block_info)
                elif isinstance(child, pyverilog.vparser.ast.Instance):
                    block_id = id(child)
                    block_type = child.__class__.__name__
                    block_code = codegen.visit(child)
                    
                    # 存储实例化信息
                    all_blocks[block_id] = (block_type, block_code, {
                        'instance_name': child.name,
                        'module_name': child.module,
                        'port_connections': {str(p.portname): str(p.argname) for p in child.portlist}
                    })
                    block_info = (block_type, block_id, block_code)
                    traverse(child, block_info)
                
                traverse_blocks(child)
    
    traverse_blocks(ast)
    return all_blocks, signal_blocks

def get_top_module_name(node):
    if isinstance(node, pyverilog.vparser.ast.Source):
        for child in node.children():
            if isinstance(child, pyverilog.vparser.ast.Description):
                for item in child.definitions:
                    if isinstance(item, pyverilog.vparser.ast.ModuleDef):
                        return item.name
    return None

def analyze_dataflow_tree(tree, flow_info):
    try:
        flow_info['tree_type'] = tree.__class__.__name__

        if isinstance(tree, pyverilog.dataflow.dataflow.DFPartselect):
            flow_info['var'] = str(tree.var)
            flow_info['msb'] = str(tree.msb) if hasattr(tree, 'msb') else None
            flow_info['lsb'] = str(tree.lsb) if hasattr(tree, 'lsb') else None

        elif isinstance(tree, pyverilog.dataflow.dataflow.DFBranch):
            if hasattr(tree, 'condnode'):
                flow_info['condition'] = str(tree.condnode)
            if hasattr(tree, 'truenode'):
                flow_info['true_value'] = str(tree.truenode)
                true_info = {}
                analyze_dataflow_tree(tree.truenode, true_info)
                flow_info['true_branch'] = true_info
            if hasattr(tree, 'falsenode'):
                flow_info['false_value'] = str(tree.falsenode)
                false_info = {}
                analyze_dataflow_tree(tree.falsenode, false_info)
                flow_info['false_branch'] = false_info

        srcs = []
        if hasattr(tree, 'children'):
            for child in tree.children():
                if hasattr(child, 'var'):
                    srcs.append(str(child.var))
                child_info = {}
                analyze_dataflow_tree(child, child_info)
                if 'src_signals' in child_info:
                    srcs.extend(child_info['src_signals'])
        flow_info['src_signals'] = list(set(srcs))

        return flow_info
    except Exception as e:
        print(f"Error when analyzing tree: {str(e)}")
        return flow_info

def find_matching_term(signal_name, terms, module_name=None):
    base_name = signal_name
    if module_name and signal_name.startswith(f"{module_name}"):
        base_name = signal_name[len(module_name)+1:]
    if '_graphrename_' in base_name:
        base_name = base_name.split('_graphrename_')[0]
    variants = [
        signal_name,
        base_name,
        f"{module_name}.{signal_name}" if module_name else signal_name,  
        f"{module_name}.{base_name}" if module_name else base_name,  
        signal_name.split('.')[-1],  
        f"{module_name}{signal_name}" if module_name else signal_name,  
        f"{module_name}_{base_name}" if module_name else base_name,  
        f"{module_name}_{signal_name.split('.')[-1]}" if module_name else signal_name.split('.')[-1],  
    ]
    
    for term_name in terms:
        term_str = str(term_name)  
        for variant in variants:
            if term_str == variant:
                return term_name  
            elif term_str.endswith('.' + variant):
                return term_name 
    
    return None

def analyze_with_dataflow(file_path, signal_blocks):
    try:
        ast = parse_verilog(file_path)
        top_module = get_top_module_name(ast)
        if not top_module:
            top_module = os.path.splitext(os.path.basename(file_path))[0]
        
        analyzer_temp = file_path + '.analyzer.temp'
        with open(file_path, 'rb') as f:
            content_bytes = f.read()
        try:
            content = content_bytes.decode()
        except UnicodeDecodeError:
            content = content_bytes.decode('utf-8')
        with open(analyzer_temp, 'w') as f:
            f.write(content)
        
        try:
            analyzer = VerilogDataflowAnalyzer(
                [analyzer_temp],
                top_module,
                [],
                [],
                analyzer_temp  # 使用新的临时文件
            )
            
            analyzer.generate()
            
            terms = analyzer.getTerms()
            binddict = analyzer.getBinddict()
            
            combined_info = {}
            if signal_blocks:
                all_dataflows = {}
                
                def process_tree(tree, flow_info):
                    signals = set()
                    
                    if tree is None:
                        return signals
                    
                    if isinstance(tree, pyverilog.dataflow.dataflow.DFTerminal):
                        signal_name = str(tree.name) if hasattr(tree, 'name') else str(tree)
                        signals.add(signal_name)
                    
                    elif isinstance(tree, pyverilog.dataflow.dataflow.DFOperator):
                        if hasattr(tree, 'nextnodes'):
                            for node in tree.nextnodes:
                                if isinstance(node, pyverilog.dataflow.dataflow.DFTerminal):
                                    signal_name = str(node)
                                    signals.add(signal_name)
                                node_signals = process_tree(node, flow_info)
                                signals.update(node_signals)
                                if node_signals:
                    
                    elif isinstance(tree, pyverilog.dataflow.dataflow.DFBranch):
                        if hasattr(tree, 'condnode'):
                            cond_signals = process_tree(tree.condnode, flow_info)
                            signals.update(cond_signals)
                        
                        if hasattr(tree, 'truenode'):
                            true_signals = process_tree(tree.truenode, flow_info)
                            signals.update(true_signals)
                        
                        if hasattr(tree, 'falsenode'):
                            false_signals = process_tree(tree.falsenode, flow_info)
                            signals.update(false_signals)
                    
                    elif isinstance(tree, pyverilog.dataflow.dataflow.DFPartselect):
                        if hasattr(tree, 'var'):
                            var_signals = process_tree(tree.var, flow_info)
                            signals.update(var_signals)
                    
                    elif hasattr(tree, 'children'):
                        for child in tree.children():
                            child_signals = process_tree(child, flow_info)
                            signals.update(child_signals)
                    
                    return signals

                module_instances = set()
                def collect_instances(node):
                    if isinstance(node, pyverilog.vparser.ast.Instance):
                        module_instances.add(node.name)
                    if hasattr(node, 'children'):
                        for child in node.children():
                            collect_instances(child)
                
                ast = parse_verilog(file_path)
                collect_instances(ast)
                
                for bind_name, bind in binddict.items():
                    signal_name = str(bind_name).split('.')[-1]
                    if signal_name not in module_instances:  
                        if isinstance(bind, list):
                            for b in bind:
                                try:
                                    flow_info = {
                                        'termname': str(bind_name),
                                        'bind_type': b.__class__.__name__,
                                        'assignment_type': 'blocking' if hasattr(b, '_assign') else 'non_blocking'
                                    }
                                    
                                    if hasattr(b, 'tree'):
                                        tree = b.tree
                                        flow_info['tree_type'] = tree.__class__.__name__
                                        if hasattr(tree, 'tostr'):
                                            flow_info['tree_str'] = tree.tostr()
                                        
                                        all_signals = process_tree(tree, flow_info)
                                        flow_info['all_signals'] = list(all_signals)
                                        
                                        for signal in all_signals:
                                            signal_name = str(signal).split('.')[-1]
                                            if signal_name not in all_dataflows:
                                                all_dataflows[signal_name] = []
                                            all_dataflows[signal_name].append(flow_info)
                                        
                                        dest_signal = str(bind_name).split('.')[-1]
                                        if dest_signal not in all_dataflows:
                                            all_dataflows[dest_signal] = []
                                        if flow_info not in all_dataflows[dest_signal]:
                                            all_dataflows[dest_signal].append(flow_info)
                                    
                                    if hasattr(b, 'alwaysinfo') and b.alwaysinfo:
                                        always = b.alwaysinfo
                                        flow_info['always_type'] = 'clockedge' if b.isClockEdge() else 'combination'
                                        if b.isClockEdge():
                                            flow_info['clock_edge'] = 'posedge' if b.getClockEdge() else 'negedge'
                                            flow_info['clock_name'] = str(b.getClockName())
                                            
                                except Exception as e:
                                    print(f"Error processing binds: {str(e)}")
                
                for signal_name, blocks in signal_blocks.items():
                    combined_info[signal_name] = {
                        'blocks': blocks,
                        'dataflow': all_dataflows.get(signal_name, [])
                    }
            
            return combined_info
            
        finally:
            if os.path.exists(analyzer_temp):
                os.remove(analyzer_temp)
                
    except Exception as e:
        return {}

def analyze_dataflow(file_path, topmodule):
    temp_file = file_path + '.dataflow.temp'
    with open(file_path, 'rb') as f:
        content_bytes = f.read()
    try:
        content = content_bytes.decode()
    except UnicodeDecodeError:
        content = content_bytes.decode('utf-8')
        
    with open(temp_file, 'w') as f:
        f.write(content)
        
    try:
        analyzer = VerilogDataflowAnalyzer(
            [temp_file],
            topmodule,
            noreorder=False,
            nobind=False,
            preprocess_include=[],
            preprocess_define=[])
            
        analyzer.generate()
        
        directives = analyzer.get_directives()
        terms = analyzer.getTerms()
        binddict = analyzer.getBinddict()

        optimizer = VerilogDataflowOptimizer(terms, binddict)

        optimizer.resolveConstant()
        resolved_terms = optimizer.getResolvedTerms()
        resolved_binddict = optimizer.getResolvedBinddict()
        constlist = optimizer.getConstlist()

        graphgen = VerilogGraphGenerator(topmodule, terms, binddict,
                                        resolved_terms, resolved_binddict, constlist, "out.png")

        for name, bindlist in binddict.items():
            for bind in bindlist:
                graphgen.generate(str(bind.dest), walk=False, identical=False,
                            step=1, do_reorder=False, delay=False, alwaysinfo=bind.alwaysinfo, withcolor=True)    
        return graphgen.graph
        
    finally:
        if os.path.exists(temp_file):
            os.remove(temp_file)
