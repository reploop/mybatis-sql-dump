package org.reploop.mybatis.sql.dump;

import org.apache.ibatis.ognl.*;
import org.reploop.mybatis.sql.operator.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Ognl Probe
 */
public class OgnlTraverse {
    private static final Logger LOG = LoggerFactory.getLogger(OgnlTraverse.class);

    public void probe(Node root, Map<String, Object> context) {
        ProbeContext probeContext = new ProbeContext(context);
        probe(root, probeContext);
        probeContext.computeAll();
        LOG.info("Parse {}", root);
    }

    private <T> Operator asOperator(T o, Function<String, Operator> fun) {
        return fun.apply(o.getClass().getSimpleName());
    }

    /**
     * It's a Postorder traversal
     *
     * @param root    current root node
     * @param context the context to collect variables.
     */
    void probe(Node root, ProbeContext context) {
        // Eval operator
        Node parent = root.jjtGetParent();
        if ((root instanceof ASTProperty || root instanceof ASTChain) && (null == parent || parent instanceof ASTAnd || parent instanceof ASTOr)) {
            context.pushOperator(new Eval());
        }
        // Collect operators
        if (root instanceof ComparisonExpression ce) {
            context.pushOperator(asOperator(ce, BinaryOperator::new));
        } else if (root instanceof ASTNot) {
            context.pushOperator(new Not());
        } else if (root instanceof BooleanExpression be) {
            context.pushOperator(asOperator(be, BinaryOperator::new));
        } else if (root instanceof ASTChain) {
            context.pushOperator(new Chain(context.operands.size()));
        } else if (root instanceof ASTNegate) {
            context.pushOperator(new Negate());
        }

        int n = root.jjtGetNumChildren();
        if (n > 0) {
            for (int i = 0; i < n; i++) {
                Node node = root.jjtGetChild(i);
                parent = node.jjtGetParent();
                if (parent instanceof ASTChain) {
                    context.peekOperator()
                            .ifPresent(operator -> {
                                if (operator instanceof Chain chain) {
                                    chain.increaseCount();
                                }
                            });
                }
                probe(node, context);
            }

        } else {
            if (root instanceof ASTConst constVal) {
                Object val = constVal.getValue();
                AtomicBoolean isSizeMethod = new AtomicBoolean(false);
                // list.size > 0
                if (val instanceof String s) {
                    if (SIZE_METHOD.equals(s)) {
                        Optional.of(constVal)
                                .map(SimpleNode::jjtGetParent)
                                .map(Node::jjtGetParent)
                                .ifPresent(node -> {
                                    if (node instanceof ASTChain) {
                                        context.peekOperator().ifPresent(operator -> {
                                            if (operator instanceof Chain chain) {
                                                chain.decreaseCount();
                                            }
                                        });
                                        context.pushOperator(new Size());
                                        isSizeMethod.set(true);
                                    }
                                });
                    }
                }
                if (!isSizeMethod.get()) {
                    context.pushOperand(val);
                }
            } else if (root instanceof ASTMethod method) {
                String methodName = method.getMethodName();
                if (SIZE_METHOD.equals(methodName)) {
                    context.peekOperator().ifPresent(operator -> {
                        if (operator instanceof Chain chain) {
                            chain.decreaseCount();
                        }
                    });
                    context.pushOperator(new Size());
                } else {
                    context.pushOperator(new UnaryOperator(methodName));
                }
            }
            LOG.info("{}", root);
        }
    }

    private static final String SIZE_METHOD = "size";
}
