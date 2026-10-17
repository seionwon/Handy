// Keychain passwords arrive on stdin, never in argv or the build log.
// Only the dedicated Handy build keychain is opened; no trust anchors change.
#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void clear_secret(void *pointer, size_t count) {
    volatile unsigned char *bytes = pointer;
    while (count--) *bytes++ = 0;
}

static int fail(const char *operation, OSStatus status) {
    fprintf(stderr, "Handy signing keychain: %s failed (%d)\n", operation, (int)status);
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 3) return fail("usage: create|unlock|import|lock keychain [p12]", errSecParam);
    SecKeychainSetUserInteractionAllowed(false);
    SecKeychainRef keychain = NULL;
    OSStatus status;
    if (!strcmp(argv[1], "lock")) {
        status = SecKeychainOpen(argv[2], &keychain);
        if (status == errSecSuccess) status = SecKeychainLock(keychain);
        if (keychain) CFRelease(keychain);
        return status == errSecSuccess ? 0 : fail("lock", status);
    }
    char *password = NULL;
    size_t capacity = 0;
    ssize_t length = getline(&password, &capacity, stdin);
    if (length <= 0 || length > 1024) {
        free(password);
        return fail("password input", errSecParam);
    }
    while (length && (password[length - 1] == '\n' || password[length - 1] == '\r')) length--;
    if (!strcmp(argv[1], "create")) {
        // SecKeychainCreate can add to the user's search list. Restore it
        // immediately; codesign receives this keychain explicitly instead.
        CFArrayRef search_list = NULL;
        status = SecKeychainCopySearchList(&search_list);
        if (status == errSecSuccess) {
            status = SecKeychainCreate(argv[2], (UInt32)length, password, false, NULL, &keychain);
            OSStatus restored = SecKeychainSetSearchList(search_list);
            if (status == errSecSuccess) status = restored;
            CFRelease(search_list);
        }
    } else {
        status = SecKeychainOpen(argv[2], &keychain);
        if (status == errSecSuccess) status = SecKeychainUnlock(keychain, (UInt32)length, password, true);
    }
    if (status != errSecSuccess) {
        clear_secret(password, capacity);
        free(password);
        if (keychain) CFRelease(keychain);
        return fail("open/create", status);
    }
    if (!strcmp(argv[1], "import") && argc == 4) {
        FILE *file = fopen(argv[3], "rb");
        if (!file) status = errSecIO;
        else {
            unsigned char bytes[65536];
            size_t count = fread(bytes, 1, sizeof(bytes), file);
            if (ferror(file) || !feof(file)) status = errSecIO;
            else {
                CFDataRef data = CFDataCreate(NULL, bytes, (CFIndex)count);
                SecExternalFormat format = kSecFormatPKCS12;
                SecExternalItemType item_type = kSecItemTypeAggregate;
                SecTrustedApplicationRef codesign = NULL;
                SecAccessRef access = NULL;
                status = SecTrustedApplicationCreateFromPath("/usr/bin/codesign", &codesign);
                if (status == errSecSuccess) {
                    CFArrayRef trusted = CFArrayCreate(NULL, (const void **)&codesign, 1, &kCFTypeArrayCallBacks);
                    status = SecAccessCreate(CFSTR("Handy local build signing"), trusted, &access);
                    CFRelease(trusted);
                }
                if (status == errSecSuccess) {
                    CFStringRef passphrase = CFStringCreateWithBytes(NULL, (const UInt8 *)password, length, kCFStringEncodingUTF8, false);
                    SecItemImportExportKeyParameters parameters = {0};
                    parameters.version = SEC_KEY_IMPORT_EXPORT_PARAMS_VERSION;
                    parameters.passphrase = passphrase;
                    parameters.accessRef = access;
                    status = SecItemImport(data, NULL, &format, &item_type, 0, &parameters, keychain, NULL);
                    CFRelease(passphrase);
                }
                if (access) CFRelease(access);
                if (codesign) CFRelease(codesign);
                CFRelease(data);
            }
            clear_secret(bytes, sizeof(bytes));
            fclose(file);
        }
    } else if (strcmp(argv[1], "create") && strcmp(argv[1], "unlock")) status = errSecParam;
    clear_secret(password, capacity);
    free(password);
    CFRelease(keychain);
    return status == errSecSuccess ? 0 : fail("import", status);
}
