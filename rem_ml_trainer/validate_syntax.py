import os
import sys

def check_file_balance(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    stack = []
    pairs = {')': '(', ']': '[', '}': '{'}
    in_string = False
    in_char = False
    in_comment = False
    in_line_comment = False
    line = 1
    col = 1
    
    i = 0
    while i < len(content):
        c = content[i]
        if c == '\n':
            line += 1
            col = 1
            in_line_comment = False
            i += 1
            continue
            
        if in_line_comment:
            i += 1
            col += 1
            continue
            
        if in_comment:
            if c == '*' and i + 1 < len(content) and content[i+1] == '/':
                in_comment = False
                i += 2
                col += 2
                continue
            i += 1
            col += 1
            continue
            
        if not in_string and not in_char:
            if c == '/' and i + 1 < len(content):
                if content[i+1] == '/':
                    in_line_comment = True
                    i += 2
                    col += 2
                    continue
                elif content[i+1] == '*':
                    in_comment = True
                    i += 2
                    col += 2
                    continue
                    
        if c == '"' and not in_char:
            if i > 0 and content[i-1] == '\\':
                pass
            else:
                in_string = not in_string
            i += 1
            col += 1
            continue
            
        if c == '\'' and not in_string:
            if i > 0 and content[i-1] == '\\':
                pass
            else:
                in_char = not in_char
            i += 1
            col += 1
            continue
            
        if not in_string and not in_char:
            if c in '({[':
                stack.append((c, line, col))
            elif c in ')}]':
                if not stack:
                    print(f'{path}: Unexpected closing {c} at line {line}, col {col}')
                    return False
                top, t_line, t_col = stack.pop()
                if pairs[c] != top:
                    print(f'{path}: Mismatched {c} at {line}:{col}, expected matching for {top} from {t_line}:{t_col}')
                    return False
        i += 1
        col += 1
        
    if stack:
        print(f'{path}: Unclosed {len(stack)} brackets. First unclosed: {stack[0]}')
        return False
    print(f'{path}: BALANCED! (Total lines: {line})')
    return True

if __name__ == '__main__':
    files = [
        'app/src/main/java/com/flashalarm/miband/domain/algorithm/RemClassifierModel.java',
        'app/src/main/java/com/flashalarm/miband/domain/algorithm/RemFeatureExtractor.kt',
        'app/src/main/java/com/flashalarm/miband/domain/algorithm/MultiModalRemEngine.kt'
    ]
    all_ok = True
    for f in files:
        if not check_file_balance(f):
            all_ok = False
    if not all_ok:
        sys.exit(1)
    print("\nALL FILES FULLY BALANCED AND VALID SYNTAX!")
