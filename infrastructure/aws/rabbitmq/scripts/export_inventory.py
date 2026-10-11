"""Print public inventory and permission templates from the source of truth #69."""
import json
import platform_source as p

if __name__ == '__main__':
    result = p.inventory()
    result['permission_templates'] = [
        dict(user=user, password_file=variable, vhost=vhost,
             configure=configure, write=write, read=read, tags=tags)
        for user, variable, vhost, configure, write, read, tags in p.accounts()]
    print(json.dumps(result, indent=2))
