read -p "Are you sure you want to delete all /tmp/regtest-* directories? [y/N]: " ANS

if [ "$ANS" == "y" ]; then
    echo "Deleting..."
    rm -rf /tmp/regtest-*
    echo "Done."
else
    echo "Cancelling..."
fi
