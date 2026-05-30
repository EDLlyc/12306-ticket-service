import sys
from datasets import Dataset

try:
    import ragas
    from ragas.metrics.collections import context_precision
    from ragas.evaluation import evaluate
    print("Ragas version:", ragas.__version__)
    
    # Let's create a mockup EvaluateResult and check if it has to_dict
    from ragas.evaluation import EvaluationResult
    dataset = Dataset.from_dict({"question": ["q1"], "answer": ["a1"], "contexts": [["c1"]], "ground_truth": ["g1"]})
    metrics = []
    res = EvaluationResult(scores=[], dataset=dataset, binary_columns=[])
    try:
        print("res.to_dict():", res.to_dict())
    except Exception as e:
        print("Exception:", str(e))
except Exception as e:
    print("Startup error:", str(e))
